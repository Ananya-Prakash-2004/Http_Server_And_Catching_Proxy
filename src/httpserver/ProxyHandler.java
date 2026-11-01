package httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class ProxyHandler implements Runnable {

    private static final int IDLE_TIMEOUT_MS = 5_000;            
    private static final int MAX_REQUESTS_PER_CONNECTION = 100;  
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ORIGIN_READ_TIMEOUT_MS = 10_000;
    private static final int MAX_CACHEABLE_BYTES = 1024 * 1024; 

    // Request headers that must not be forwarded (hop-by-hop, RFC 7230 section 6.1).
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization", "proxy-authenticate");

    
    private static final Set<String> CLIENT_CONDITIONALS = Set.of(
            "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range", "range");

    
    public static volatile boolean verbose = true;

    
    private static final OriginPool ORIGIN_POOL = new OriginPool();

    private final Socket clientSocket;
    private final LruCache<String, CacheEntry> cache;

    private static void log(String message) {
        if (verbose) {
            System.out.println(message);
        }
    }

    public ProxyHandler(Socket clientSocket, LruCache<String, CacheEntry> cache) {
        this.clientSocket = clientSocket;
        this.cache = cache;
    }

    @Override
    public void run() {
        try (Socket client = clientSocket;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            client.setSoTimeout(IDLE_TIMEOUT_MS);
            client.setTcpNoDelay(true); 

        
            RequestReader reader = new RequestReader(in);
            int served = 0;

            while (served < MAX_REQUESTS_PER_CONNECTION) {
                HttpRequest request;
                try {
                    String headerText = reader.readHeaderBlock();
                    if (headerText == null) {
                        break; 
                    }
                    request = HttpRequestParser.parse(headerText);
                } catch (HttpParseException e) {
                    sendError(out, e.getStatusCode(), e.getReason()); 
                    break;
                } catch (SocketTimeoutException e) {
                    break; 
                }
                served++;

                boolean keepAlive = wantsKeepAlive(request)
                        && !hasRequestBody(request)
                        && served < MAX_REQUESTS_PER_CONNECTION;

                boolean connectionReusable = serve(out, request, keepAlive);
                if (!keepAlive || !connectionReusable) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Proxy connection error: " + e.getMessage());
        }
    }

    
    private boolean serve(OutputStream out, HttpRequest request, boolean keepAlive) throws IOException {
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendError(out, 405, "Method Not Allowed");
            return false;
        }
        boolean headOnly = method.equals("HEAD");

       
        URI target;
        try {
            target = new URI(request.getPath());
        } catch (URISyntaxException e) {
            sendError(out, 400, "Bad Request");
            return false;
        }
        if (target.getScheme() == null || !target.getScheme().equalsIgnoreCase("http")
                || target.getHost() == null) {
            sendError(out, 400, "Bad Request");
            return false;
        }

        String host = target.getHost();
        int port = target.getPort() == -1 ? 80 : target.getPort();
        String originPath = target.getRawPath();
        if (originPath == null || originPath.isEmpty()) {
            originPath = "/";
        }
        if (target.getRawQuery() != null) {
            originPath += "?" + target.getRawQuery();
        }

        return handle(out, request, method, headOnly, host, port, originPath,
                cacheKey(host, port, originPath), keepAlive);
    }

    // Same rules as the team's ConnectionHandler.
    private static boolean wantsKeepAlive(HttpRequest request) {
        String header = request.getHeader("connection");
        String tokens = header == null ? "" : header.toLowerCase();
        if ("HTTP/1.1".equals(request.getVersion())) {
            return !tokens.contains("close");
        }
        return tokens.contains("keep-alive");
    }

    private static boolean hasRequestBody(HttpRequest request) {
        String length = request.getHeader("content-length");
        boolean hasLength = length != null && !length.trim().equals("0");
        return hasLength || request.getHeader("transfer-encoding") != null;
    }

    private boolean handle(OutputStream out, HttpRequest request, String method, boolean headOnly,
                           String host, int port, String originPath, String key,
                           boolean keepAlive) throws IOException {

        
        long now = System.currentTimeMillis();
        CacheControl reqCc = CacheControl.parse(request.getHeader("cache-control"));
        // "Pragma: no-cache" is the HTTP/1.0 spelling; it only counts if there is no Cache-Control.
        boolean pragmaNoCache = request.getHeader("cache-control") == null
                && request.getHeader("pragma") != null
                && request.getHeader("pragma").toLowerCase().contains("no-cache");
        boolean bypass = reqCc.has("no-cache") || pragmaNoCache; // client wants a checked / fresh copy
        boolean reqNoStore = reqCc.has("no-store");
        long reqMaxAge = reqCc.seconds("max-age");               // -1 if absent
        boolean hasAuthorization = request.getHeader("authorization") != null;

        
        CacheEntry entry = cache.get(key);
        String why;
        if (entry == null) {
            why = "MISS";
        } else if (bypass) {
            why = "BYPASS (client sent no-cache)";
        } else if (entry.isFresh(now, reqMaxAge)) {
            long age = entry.currentAgeSeconds(now);
            log("CACHE HIT  " + method + " " + key + " (age " + age
                    + "s, fresh for " + entry.getFreshnessLifetimeSeconds() + "s)");
            ProxyStats.HITS.incrementAndGet();
            respond(out, request, entry.getResponse(), headOnly, "HIT", age, keepAlive);
            return true;
        } else {
            why = "STALE";
        }

        
        if (reqCc.has("only-if-cached")) {
            sendError(out, 504, "Gateway Timeout");
            return false;
        }

        
        boolean canRevalidate = entry != null && (entry.getEtag() != null || entry.getLastModified() != null);
        String[] validators = canRevalidate ? new String[]{entry.getEtag(), entry.getLastModified()} : null;
        log("CACHE " + why + " " + method + " " + key
                + (canRevalidate ? "  -> revalidating with the origin" : ""));

        ProxyResponse fromOrigin = fetchFromOrigin(out, request, method, host, port, originPath, validators);
        if (fromOrigin == null) {
            return false; 
        }
        long received = System.currentTimeMillis();
        int status = fromOrigin.getStatusCode();

        
        if (canRevalidate && status == 304) {
            ProxyResponse merged = entry.getResponse().withHeadersFrom(fromOrigin);
            CacheEntry refreshed = CachePolicy.evaluate(merged, hasAuthorization, received);
            if (refreshed != null) {
                cache.put(key, refreshed);
            } else {
                cache.remove(key); 
            }
            log("  origin said 304 Not Modified: cached body reused, freshness restarted");
            ProxyStats.REVALIDATED.incrementAndGet();
            respond(out, request, merged, headOnly, "REVALIDATED",
                    refreshed != null ? refreshed.currentAgeSeconds(received) : 0, keepAlive);
            return true;
        }

        if (status == 304) {
            ProxyStats.MISSES.incrementAndGet();
            out.write(fromOrigin.toBytes(headOnly, "MISS", -1, keepAlive));
            out.flush();
            return true;
        }

        ProxyStats.MISSES.incrementAndGet();
        if (headOnly) {
            if (canRevalidate && status == 200) {
                cache.remove(key); 
            }
        } else if (!reqNoStore && status < 500) {
            CacheEntry toStore = CachePolicy.evaluate(fromOrigin, hasAuthorization, received);
            if (toStore != null && fromOrigin.bodySize() <= MAX_CACHEABLE_BYTES) {
                cache.put(key, toStore);
            } else {
                cache.remove(key);
            }
        }

        if (canRevalidate) {
            respond(out, request, fromOrigin, headOnly, "MISS", -1, keepAlive);
        } else {
            out.write(fromOrigin.toBytes(headOnly, "MISS", -1, keepAlive)); 
            out.flush();
        }
        return true;
    }

    
    private void respond(OutputStream out, HttpRequest request, ProxyResponse response,
                         boolean headOnly, String xCache, long ageSeconds, boolean keepAlive) throws IOException {
        if (CachePolicy.clientConditionMatches(request.getHeader("if-none-match"),
                request.getHeader("if-modified-since"), response)) {
            log("  client's validator matches: sending 304 Not Modified");
            ProxyStats.NOT_MODIFIED_SENT.incrementAndGet();
            out.write(ProxyResponse.notModifiedFrom(response).toBytes(true, xCache, ageSeconds, keepAlive));
        } else {
            out.write(response.toBytes(headOnly, xCache, ageSeconds, keepAlive));
        }
        out.flush();
    }

    
    static String cacheKey(String host, int port, String originPath) {
        return "http://" + host.toLowerCase() + (port == 80 ? "" : ":" + port) + originPath;
    }

    
    private ProxyResponse fetchFromOrigin(OutputStream clientOut, HttpRequest request, String method,
                                          String host, int port, String originPath,
                                          String[] validators) throws IOException {
        byte[] requestBytes = buildOriginRequest(request, method, host, port, originPath, validators)
                .getBytes(StandardCharsets.ISO_8859_1);

        for (int attempt = 0; attempt < 2; attempt++) {
            // Attempt 0 may use an idle pooled connection; attempt 1 always uses a fresh one.
            Socket origin = attempt == 0 ? ORIGIN_POOL.borrow(host, port) : null;
            boolean reused = origin != null;
            if (!reused) {
                origin = new Socket();
                try {
                    origin.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                } catch (SocketTimeoutException e) {
                    OriginPool.closeQuietly(origin);
                    sendError(clientOut, 504, "Gateway Timeout");
                    return null;
                } catch (IOException e) { // UnknownHostException, ConnectException, ...
                    OriginPool.closeQuietly(origin);
                    sendError(clientOut, 502, "Bad Gateway");
                    return null;
                }
            }

            try {
                origin.setSoTimeout(ORIGIN_READ_TIMEOUT_MS);
                origin.setTcpNoDelay(true);
                OutputStream originOut = origin.getOutputStream();
                originOut.write(requestBytes);
                originOut.flush();
                ProxyResponse response = ResponseReader.read(origin.getInputStream(), method.equals("HEAD"));
                if (response.isOriginConnectionReusable()) {
                    ORIGIN_POOL.release(host, port, origin);
                } else {
                    OriginPool.closeQuietly(origin);
                }
                return response;
            } catch (SocketTimeoutException e) {
                OriginPool.closeQuietly(origin);
                sendError(clientOut, 504, "Gateway Timeout");
                return null;
            } catch (IOException e) {
                OriginPool.closeQuietly(origin);
                if (reused) {
                    continue; 
                }
                System.err.println("Bad response from origin " + host + ":" + port + " - " + e.getMessage());
                sendError(clientOut, 502, "Bad Gateway");
                return null;
            }
        }
        sendError(clientOut, 502, "Bad Gateway"); 
        return null;
    }

    private String buildOriginRequest(HttpRequest request, String method, String host, int port,
                                      String originPath, String[] validators) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(originPath).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(port == 80 ? host : host + ":" + port).append("\r\n");

        for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
            String name = h.getKey(); 
            if (name.equals("host") || HOP_BY_HOP.contains(name)) {
                continue;
            }
            if (validators != null && CLIENT_CONDITIONALS.contains(name)) {
                continue; 
            }
            sb.append(name).append(": ").append(h.getValue()).append("\r\n");
        }
        if (validators != null) {
            if (validators[0] != null) {
                sb.append("If-None-Match: ").append(validators[0]).append("\r\n");
            }
            if (validators[1] != null) {
                sb.append("If-Modified-Since: ").append(validators[1]).append("\r\n");
            }
        }
        sb.append("\r\n"); 
        return sb.toString();
    }

    private void sendError(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}

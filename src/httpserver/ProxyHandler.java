package httpserver;

import java.io.ByteArrayOutputStream;
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

/**
 * Forward proxy handler (Step 3: Cache-Control and freshness).
 *
 * Flow: read request -> parse -> look in the cache -> serve if the entry is fresh,
 * otherwise fetch the whole response from the origin (ResponseReader), decide
 * with CachePolicy whether it may be stored, and send it to the client.
 * Every response carries "X-Cache: HIT" or "X-Cache: MISS" so caching is easy to
 * see in tests and benchmarks; cached responses also carry an Age header.
 *
 * Stale entries are simply refetched in full for now. Step 4 turns that refetch
 * into a conditional request (If-None-Match / If-Modified-Since) so the origin
 * can answer 304 Not Modified.
 */
public class ProxyHandler implements Runnable {

    private static final int CLIENT_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ORIGIN_READ_TIMEOUT_MS = 10_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_CACHEABLE_BYTES = 1024 * 1024; // don't cache bodies over 1 MB

    // Request headers that must not be forwarded (hop-by-hop, RFC 7230 section 6.1).
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization", "proxy-authenticate");

    private final Socket clientSocket;
    private final LruCache<String, CacheEntry> cache;

    public ProxyHandler(Socket clientSocket, LruCache<String, CacheEntry> cache) {
        this.clientSocket = clientSocket;
        this.cache = cache;
    }

    @Override
    public void run() {
        try (Socket client = clientSocket;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            client.setSoTimeout(CLIENT_TIMEOUT_MS);

            String headerText = readHeaderBlock(in);
            if (headerText == null) {
                return; // client closed or sent nothing usable
            }

            HttpRequest request;
            try {
                request = HttpRequestParser.parser(headerText);
            } catch (IllegalArgumentException e) {
                sendError(out, 400, "Bad Request");
                return;
            }

            String method = request.getMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendError(out, 405, "Method Not Allowed");
                return;
            }
            boolean headOnly = method.equals("HEAD");

            // A proxy receives absolute-form targets: GET http://host:port/path HTTP/1.1
            URI target;
            try {
                target = new URI(request.getPath());
            } catch (URISyntaxException e) {
                sendError(out, 400, "Bad Request");
                return;
            }
            if (target.getScheme() == null || !target.getScheme().equalsIgnoreCase("http")
                    || target.getHost() == null) {
                sendError(out, 400, "Bad Request");
                return;
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

            String key = cacheKey(host, port, originPath);

            // --- What does the client's own Cache-Control say? ---
            long now = System.currentTimeMillis();
            CacheControl reqCc = CacheControl.parse(request.getHeader("cache-control"));
            // "Pragma: no-cache" is the HTTP/1.0 spelling; it only counts if there is no Cache-Control.
            boolean pragmaNoCache = request.getHeader("cache-control") == null
                    && request.getHeader("pragma") != null
                    && request.getHeader("pragma").toLowerCase().contains("no-cache");
            boolean bypass = reqCc.has("no-cache") || pragmaNoCache; // client wants a fresh copy
            boolean reqNoStore = reqCc.has("no-store");
            long reqMaxAge = reqCc.seconds("max-age");               // -1 if absent
            boolean hasAuthorization = request.getHeader("authorization") != null;

            // 1. Cache lookup (a HEAD can be answered from a cached GET: same resource).
            CacheEntry entry = cache.get(key);
            String reason;
            if (entry == null) {
                reason = "MISS";
                ProxyStats.MISSES.incrementAndGet();
            } else if (bypass) {
                reason = "BYPASS (client sent no-cache)";
                ProxyStats.BYPASS.incrementAndGet();
            } else if (entry.isFresh(now, reqMaxAge)) {
                long age = entry.currentAgeSeconds(now);
                System.out.println("CACHE HIT  " + method + " " + key + " (age " + age
                        + "s, fresh for " + entry.getFreshnessLifetimeSeconds() + "s)");
                ProxyStats.HITS.incrementAndGet();
                out.write(entry.getResponse().toBytes(headOnly, "HIT", age));
                out.flush();
                return;
            } else {
                reason = "STALE";
                ProxyStats.STALE.incrementAndGet();
            }

            // "only-if-cached": the client forbids contacting the origin (RFC 7234 5.2.1.7).
            if (reqCc.has("only-if-cached")) {
                sendError(out, 504, "Gateway Timeout");
                return;
            }

            // 2. Fetch from the origin.
            System.out.println("CACHE " + reason + " " + method + " " + key);
            ProxyResponse fresh = fetchFromOrigin(out, request, method, host, port, originPath);
            if (fresh == null) {
                return; // an error response was already sent to the client
            }

            // 3. Store it if the rules allow (never for HEAD, or if the client said no-store).
            //    A response that may not be stored also removes any older copy of the URL,
            //    except 5xx errors, which say nothing about the older copy.
            if (!headOnly && !reqNoStore && fresh.getStatusCode() < 500) {
                CacheEntry toStore = CachePolicy.evaluate(fresh, hasAuthorization, System.currentTimeMillis());
                if (toStore != null && fresh.bodySize() <= MAX_CACHEABLE_BYTES) {
                    cache.put(key, toStore);
                } else {
                    cache.remove(key);
                }
            }

            out.write(fresh.toBytes(headOnly, "MISS"));
            out.flush();

        } catch (IOException e) {
            System.err.println("Proxy connection error: " + e.getMessage());
        }
    }

    /** Normalised cache key: scheme + lowercase host + port (if not 80) + path and query. */
    static String cacheKey(String host, int port, String originPath) {
        return "http://" + host.toLowerCase() + (port == 80 ? "" : ":" + port) + originPath;
    }

    /**
     * Connects to the origin, sends the request and reads the complete response.
     * On failure sends 502 / 504 to the client itself and returns null.
     */
    private ProxyResponse fetchFromOrigin(OutputStream clientOut, HttpRequest request, String method,
                                          String host, int port, String originPath) throws IOException {
        try (Socket origin = new Socket()) {
            try {
                origin.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } catch (SocketTimeoutException e) {
                sendError(clientOut, 504, "Gateway Timeout");
                return null;
            } catch (IOException e) { // UnknownHostException, ConnectException, ...
                sendError(clientOut, 502, "Bad Gateway");
                return null;
            }
            origin.setSoTimeout(ORIGIN_READ_TIMEOUT_MS);

            try {
                OutputStream originOut = origin.getOutputStream();
                originOut.write(buildOriginRequest(request, method, host, port, originPath)
                        .getBytes(StandardCharsets.ISO_8859_1));
                originOut.flush();
                return ResponseReader.read(origin.getInputStream(), method.equals("HEAD"));
            } catch (SocketTimeoutException e) {
                sendError(clientOut, 504, "Gateway Timeout");
                return null;
            } catch (IOException e) {
                System.err.println("Bad response from origin " + host + ":" + port + " - " + e.getMessage());
                sendError(clientOut, 502, "Bad Gateway");
                return null;
            }
        }
    }

    private String buildOriginRequest(HttpRequest request, String method,
                                      String host, int port, String originPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(originPath).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(port == 80 ? host : host + ":" + port).append("\r\n");

        for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
            String name = h.getKey(); // already lowercased by HttpRequest.addHeader
            if (name.equals("host") || HOP_BY_HOP.contains(name)) {
                continue;
            }
            sb.append(name).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    /** Reads until \r\n\r\n; returns the header text without the blank line, or null. */
    private String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        while (true) {
            int n;
            try {
                n = in.read(chunk);
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (n == -1) {
                return null;
            }
            acc.write(chunk, 0, n);
            String text = acc.toString(StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            if (end != -1) {
                return text.substring(0, end);
            }
            if (acc.size() > MAX_HEADER_BYTES) {
                return null;
            }
        }
    }

    private void sendError(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}

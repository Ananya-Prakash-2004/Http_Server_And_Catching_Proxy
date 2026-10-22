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

/**
 * Forward proxy handler (Step 4: ETag validation and conditional GET).
 *
 * For each request there are three possible paths:
 *   HIT          a fresh copy is in the cache: answer without contacting the origin.
 *   REVALIDATED  the copy is stale (or the client asked for a fresh one) but has an
 *                ETag / Last-Modified: ask the origin with If-None-Match /
 *                If-Modified-Since. A 304 means "your copy is still good": the stored
 *                body is reused and its freshness restarts.
 *   MISS         nothing usable cached (or the resource changed): full response from
 *                the origin, stored if CachePolicy allows.
 *
 * Independently of that, if the CLIENT sent its own If-None-Match / If-Modified-Since
 * and the response we are about to send matches, we send a bodyless 304 instead of the 200.
 * Every response carries an X-Cache header; cached ones also carry an Age header.
 */
public class ProxyHandler implements Runnable {

    private static final int CLIENT_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ORIGIN_READ_TIMEOUT_MS = 10_000;
    private static final int MAX_CACHEABLE_BYTES = 1024 * 1024; // don't cache bodies over 1 MB

    // Request headers that must not be forwarded (hop-by-hop, RFC 7230 section 6.1).
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization", "proxy-authenticate");

    // When the proxy revalidates, it replaces the client's own conditional headers with its own.
    private static final Set<String> CLIENT_CONDITIONALS = Set.of(
            "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range", "range");

    /** Per-request log lines on the console. The benchmark switches this off. */
    public static volatile boolean verbose = true;

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

            client.setSoTimeout(CLIENT_TIMEOUT_MS);

            // The team's RequestReader buffers bytes until the blank line that ends the headers,
            // and HttpRequestParser turns that text into an HttpRequest.
            HttpRequest request;
            try {
                String headerText = new RequestReader(in).readHeaderBlock();
                if (headerText == null) {
                    return; // client closed the connection without sending anything
                }
                request = HttpRequestParser.parse(headerText);
            } catch (HttpParseException e) {
                sendError(out, e.getStatusCode(), e.getReason()); // 400, 414, 431, 505
                return;
            } catch (SocketTimeoutException e) {
                return; // client connected but sent nothing in time
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

            handle(out, request, method, headOnly, host, port, originPath,
                    cacheKey(host, port, originPath));

        } catch (IOException e) {
            System.err.println("Proxy connection error: " + e.getMessage());
        }
    }

    private void handle(OutputStream out, HttpRequest request, String method, boolean headOnly,
                        String host, int port, String originPath, String key) throws IOException {

        // --- What does the client's own Cache-Control say? ---
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

        // 1. Cache lookup (a HEAD can be answered from a cached GET: same resource).
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
            respond(out, request, entry.getResponse(), headOnly, "HIT", age);
            return;
        } else {
            why = "STALE";
        }

        // "only-if-cached": the client forbids contacting the origin (RFC 7234 5.2.1.7).
        if (reqCc.has("only-if-cached")) {
            sendError(out, 504, "Gateway Timeout");
            return;
        }

        // 2. Go to the origin. If we hold a copy with a validator, make the request conditional.
        boolean canRevalidate = entry != null && (entry.getEtag() != null || entry.getLastModified() != null);
        String[] validators = canRevalidate ? new String[]{entry.getEtag(), entry.getLastModified()} : null;
        log("CACHE " + why + " " + method + " " + key
                + (canRevalidate ? "  -> revalidating with the origin" : ""));

        ProxyResponse fromOrigin = fetchFromOrigin(out, request, method, host, port, originPath, validators);
        if (fromOrigin == null) {
            return; // an error response was already sent to the client
        }
        long received = System.currentTimeMillis();
        int status = fromOrigin.getStatusCode();

        // 3a. 304: our stored copy is still good. Refresh its headers and freshness, reuse its body.
        if (canRevalidate && status == 304) {
            ProxyResponse merged = entry.getResponse().withHeadersFrom(fromOrigin);
            CacheEntry refreshed = CachePolicy.evaluate(merged, hasAuthorization, received);
            if (refreshed != null) {
                cache.put(key, refreshed);
            } else {
                cache.remove(key); // the 304 turned out to carry no-store etc.
            }
            log("  origin said 304 Not Modified: cached body reused, freshness restarted");
            ProxyStats.REVALIDATED.incrementAndGet();
            respond(out, request, merged, headOnly, "REVALIDATED",
                    refreshed != null ? refreshed.currentAgeSeconds(received) : 0);
            return;
        }

        // 3b. A 304 here answers the client's own conditional request (we did not revalidate): relay it.
        if (status == 304) {
            ProxyStats.MISSES.incrementAndGet();
            out.write(fromOrigin.toBytes(headOnly, "MISS"));
            out.flush();
            return;
        }

        // 3c. A full response. Store it if the rules allow (never for HEAD, or if the client said no-store).
        //     A response that may not be stored also removes any older copy of the URL,
        //     except 5xx errors, which say nothing about the older copy.
        ProxyStats.MISSES.incrementAndGet();
        if (headOnly) {
            if (canRevalidate && status == 200) {
                cache.remove(key); // HEAD shows the resource changed; the old body is useless
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
            // we replaced the client's conditionals when asking the origin, so check them ourselves
            respond(out, request, fromOrigin, headOnly, "MISS", -1);
        } else {
            out.write(fromOrigin.toBytes(headOnly, "MISS")); // the origin already saw the client's conditionals
            out.flush();
        }
    }

    /**
     * Sends a response we hold ourselves, turning it into a bodyless 304 if the client's
     * If-None-Match / If-Modified-Since say the client already has this version.
     */
    private void respond(OutputStream out, HttpRequest request, ProxyResponse response,
                         boolean headOnly, String xCache, long ageSeconds) throws IOException {
        if (CachePolicy.clientConditionMatches(request.getHeader("if-none-match"),
                request.getHeader("if-modified-since"), response)) {
            log("  client's validator matches: sending 304 Not Modified");
            ProxyStats.NOT_MODIFIED_SENT.incrementAndGet();
            out.write(ProxyResponse.notModifiedFrom(response).toBytes(true, xCache, ageSeconds));
        } else {
            out.write(response.toBytes(headOnly, xCache, ageSeconds));
        }
        out.flush();
    }

    /** Normalised cache key: scheme + lowercase host + port (if not 80) + path and query. */
    static String cacheKey(String host, int port, String originPath) {
        return "http://" + host.toLowerCase() + (port == 80 ? "" : ":" + port) + originPath;
    }

    /**
     * Connects to the origin, sends the request and reads the complete response.
     * On failure sends 502 / 504 to the client itself and returns null.
     *
     * @param validators {etag, lastModified} of our cached copy (either may be null) to make the
     *                   request conditional, or null to forward the client's own headers untouched
     */
    private ProxyResponse fetchFromOrigin(OutputStream clientOut, HttpRequest request, String method,
                                          String host, int port, String originPath,
                                          String[] validators) throws IOException {
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
                originOut.write(buildOriginRequest(request, method, host, port, originPath, validators)
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

    private String buildOriginRequest(HttpRequest request, String method, String host, int port,
                                      String originPath, String[] validators) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(originPath).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(port == 80 ? host : host + ":" + port).append("\r\n");

        for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
            String name = h.getKey(); // already lowercased by HttpRequest.addHeader
            if (name.equals("host") || HOP_BY_HOP.contains(name)) {
                continue;
            }
            if (validators != null && CLIENT_CONDITIONALS.contains(name)) {
                continue; // revalidating: our validators replace the client's (and no Range)
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
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    private void sendError(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}

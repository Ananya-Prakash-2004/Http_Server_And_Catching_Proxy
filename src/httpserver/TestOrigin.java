package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny origin server for testing the proxy. It builds each response from
 * query-string parameters, so one URL decides exactly what headers come back.
 *
 *   status=404             status code (default 200); "200|500|200" = a different status per request
 *   cc=max-age%3D60        Cache-Control value. "a|b|c" = a on the 1st request for this
 *                          exact URL, b on the 2nd, c from the 3rd on ("" = no header)
 *   expires=60             Expires header, N seconds from now (negative = in the past)
 *   expiresraw=0           Expires header with this literal text
 *   etag=v1                ETag (quoted for you); "v1|v2" = v1 on the 1st request, v2 afterwards (a change)
 *   lm=3600                Last-Modified = server start minus N seconds
 *   vary=Accept-Encoding   Vary header                    cookie=a%3D1  Set-Cookie header
 *   delay=200              wait N ms before replying      size=5000  pad the body to N bytes
 *
 * It also answers conditional requests: If-None-Match matching etag, or If-Modified-Since
 * not older than lm, gives "304 Not Modified" (used from Step 4 on).
 * Every request is logged, so you can see when the proxy really reached the origin.
 *
 * Usage: java httpserver.TestOrigin [port]     (default 9090)
 */
public final class TestOrigin {
    private final ServerSocket serverSocket;
    private final long startMs = (System.currentTimeMillis() / 1000) * 1000;
    private final AtomicInteger totalRequests = new AtomicInteger();
    private final AtomicInteger conditionalRequests = new AtomicInteger();
    private final AtomicInteger notModifiedReplies = new AtomicInteger();
    private final Map<String, AtomicInteger> perUrl = new ConcurrentHashMap<>();
    private final boolean quiet;

    public TestOrigin(ServerSocket serverSocket, boolean quiet) {
        this.serverSocket = serverSocket;
        this.quiet = quiet;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 9090;
        ServerSocket ss = new ServerSocket(port);
        System.out.println("Test origin listening on port " + port);
        new TestOrigin(ss, false).serve();
    }

    /** Number of requests that actually reached this origin. */
    public int requestCount() {
        return totalRequests.get();
    }

    /** Requests that carried If-None-Match or If-Modified-Since. */
    public int conditionalCount() {
        return conditionalRequests.get();
    }

    /** 304 replies this origin has sent. */
    public int notModifiedCount() {
        return notModifiedReplies.get();
    }

    public void serve() {
        try {
            while (true) {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s));
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            // closed
        }
    }

    private void handle(Socket s) {
        try (s) {
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            String text;
            do {
                int n = in.read(buf);
                if (n == -1) {
                    return;
                }
                acc.write(buf, 0, n);
                text = acc.toString(StandardCharsets.ISO_8859_1);
            } while (!text.contains("\r\n\r\n"));

            String[] lines = text.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            String method = requestLine[0];
            String target = requestLine[1];
            Map<String, String> reqHeaders = new HashMap<>();
            for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    reqHeaders.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(c + 1).trim());
                }
            }

            int q = target.indexOf('?');
            Map<String, String> p = new HashMap<>();
            if (q >= 0) {
                for (String pair : target.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        p.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                    }
                }
            }

            int n = totalRequests.incrementAndGet();
            int k = perUrl.computeIfAbsent(target, x -> new AtomicInteger()).incrementAndGet();

            String[] statusSeq = p.getOrDefault("status", "200").split("\\|");
            int status = Integer.parseInt(statusSeq[Math.min(k - 1, statusSeq.length - 1)]);
            long now = System.currentTimeMillis();

            // Cache-Control, optionally a different value per successive request for this URL
            String cc = null;
            if (p.containsKey("cc")) {
                String[] seq = p.get("cc").split("\\|", -1);
                cc = seq[Math.min(k - 1, seq.length - 1)];
            }

            String etag = null;
            if (p.containsKey("etag")) {
                String[] seq = p.get("etag").split("\\|");
                etag = seq[Math.min(k - 1, seq.length - 1)];
            }
            if (etag != null && !etag.startsWith("\"") && !etag.startsWith("W/")) {
                etag = "\"" + etag + "\"";
            }
            Long lmMs = p.containsKey("lm") ? startMs - Long.parseLong(p.get("lm")) * 1000 : null;

            // Conditional request handling
            boolean conditional = reqHeaders.containsKey("if-none-match") || reqHeaders.containsKey("if-modified-since");
            boolean notModified = false;
            if (status == 200) {
                String inm = reqHeaders.get("if-none-match");
                if (inm != null) {
                    if (etag != null) {
                        for (String tag : inm.split(",")) {
                            String t = tag.trim();
                            if (t.equals("*") || stripWeak(t).equals(stripWeak(etag))) {
                                notModified = true;
                            }
                        }
                    }
                } else if (reqHeaders.containsKey("if-modified-since") && lmMs != null) {
                    Long ims = HttpDates.parse(reqHeaders.get("if-modified-since"));
                    notModified = ims != null && ims >= lmMs;
                }
            }
            if (conditional) {
                conditionalRequests.incrementAndGet();
            }
            if (notModified) {
                status = 304;
                notModifiedReplies.incrementAndGet();
            }

            int delay = Integer.parseInt(p.getOrDefault("delay", "0"));
            if (delay > 0) {
                Thread.sleep(delay);
            }

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n");
            h.append("Date: ").append(HttpDates.format(now)).append("\r\n");
            if (cc != null && !cc.isEmpty()) {
                h.append("Cache-Control: ").append(cc).append("\r\n");
            }
            if (p.containsKey("expires")) {
                h.append("Expires: ").append(HttpDates.format(now + Long.parseLong(p.get("expires")) * 1000)).append("\r\n");
            }
            if (p.containsKey("expiresraw")) {
                h.append("Expires: ").append(p.get("expiresraw")).append("\r\n");
            }
            if (etag != null) {
                h.append("ETag: ").append(etag).append("\r\n");
            }
            if (lmMs != null) {
                h.append("Last-Modified: ").append(HttpDates.format(lmMs)).append("\r\n");
            }
            if (p.containsKey("vary")) {
                h.append("Vary: ").append(p.get("vary")).append("\r\n");
            }
            if (p.containsKey("cookie")) {
                h.append("Set-Cookie: ").append(p.get("cookie")).append("\r\n");
            }

            byte[] body = new byte[0];
            if (status != 304 && status != 204) {
                StringBuilder b = new StringBuilder("origin response #" + n + " (request " + k + " for this URL): " + target + "\n");
                int size = Integer.parseInt(p.getOrDefault("size", "0"));
                while (b.length() < size) {
                    b.append('x');
                }
                body = b.toString().getBytes(StandardCharsets.UTF_8);
                h.append("Content-Type: text/plain\r\n");
                h.append("Content-Length: ").append(body.length).append("\r\n");
            }
            h.append("Connection: close\r\n\r\n");

            if (!quiet) {
                System.out.println("[origin] #" + n + " " + method + " " + target + " -> " + status
                        + (conditional ? " (conditional request)" : "")
                        + (cc != null && !cc.isEmpty() ? " cc=" + cc : ""));
            }

            OutputStream out = s.getOutputStream();
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!method.equals("HEAD")) {
                out.write(body);
            }
            out.flush();
        } catch (IOException | InterruptedException | RuntimeException e) {
            // test helper: ignore broken connections and malformed test URLs
        }
    }

    private static String stripWeak(String tag) {
        return tag.startsWith("W/") ? tag.substring(2) : tag;
    }

    private static String reasonFor(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 304: return "Not Modified";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status " + status;
        }
    }
}

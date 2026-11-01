package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * End-to-end test: starts TestOrigin and the proxy inside this JVM and checks, request by
 * request, whether the proxy answered from its cache (X-Cache: HIT) or went to the origin.
 * Run: java -cp out httpserver.ProxyCacheTest
 */
public class ProxyCacheTest {
    static int passed = 0;
    static int failed = 0;
    static int originPort;
    static int proxyPort;
    static TestOrigin origin;

    static class Resp {
        int status;
        Map<String, String> headers = new HashMap<>();
        String body = "";

        String xcache() {
            return headers.get("x-cache");
        }
    }

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   - " + name);
        } else {
            failed++;
            System.out.println("  FAIL - " + name);
        }
    }

    /** URL of the test origin; kv = alternating parameter names and values. */
    static String url(String path, String... kv) throws IOException {
        StringBuilder sb = new StringBuilder("http://localhost:" + originPort + "/" + path);
        for (int i = 0; i < kv.length; i += 2) {
            sb.append(i == 0 ? '?' : '&').append(kv[i]).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Resp send(int port, String method, String url, String... extraHeaders) throws IOException {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(15000);
            StringBuilder req = new StringBuilder(method + " " + url + " HTTP/1.1\r\nHost: test\r\nConnection: close\r\n");
            for (String h : extraHeaders) {
                req.append(h).append("\r\n");
            }
            req.append("\r\n");
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            String all = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int split = all.indexOf("\r\n\r\n");
            String head = split >= 0 ? all.substring(0, split) : all;
            Resp r = new Resp();
            r.body = split >= 0 ? all.substring(split + 4) : "";
            String[] lines = head.split("\r\n");
            r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
            }
            return r;
        }
    }

    static Resp get(String url, String... extraHeaders) throws IOException {
        return send(proxyPort, "GET", url, extraHeaders);
    }

    /** Two plain GETs: expect the given X-Cache values and number of origin contacts. */
    static void twice(String name, String url, String first, String second, int originHits) throws IOException {
        int before = origin.requestCount();
        Resp a = get(url);
        Resp b = get(url);
        check(name + "  [" + first + " then " + second + ", origin contacted " + originHits + "x]",
                first.equals(a.xcache()) && second.equals(b.xcache())
                        && origin.requestCount() - before == originHits);
    }


    /** Reads exactly one response from a persistent connection (framed by Content-Length). Null at EOF. */
    static Resp readOne(InputStream in, boolean headRequest) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int[] pattern = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            if (b == pattern[matched]) {
                if (++matched == 4) {
                    break;
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        if (matched < 4) {
            return null;
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        Resp r = new Resp();
        r.status = Integer.parseInt(lines[0].split(" ")[1]);
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                r.headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
            }
        }
        String cl = r.headers.get("content-length");
        if (!headRequest && r.status != 304 && r.status != 204 && cl != null) {
            r.body = new String(in.readNBytes(Integer.parseInt(cl)), StandardCharsets.ISO_8859_1);
        }
        return r;
    }

    static byte[] raw(String method, String url, String version, String... extraHeaders) {
        StringBuilder sb = new StringBuilder(method + " " + url + " " + version + "\r\nHost: test\r\n");
        for (String h : extraHeaders) {
            sb.append(h).append("\r\n");
        }
        return sb.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static Socket open() throws IOException {
        Socket s = new Socket("localhost", proxyPort);
        s.setSoTimeout(15000);
        return s;
    }

    static void originConnectionTests() throws Exception {
        // 20 sequential cache misses: the proxy should keep using one connection to the origin
        int c0 = origin.connectionCount();
        int r0 = origin.requestCount();
        boolean allOk = true;
        for (int i = 0; i < 20; i++) {
            Resp r = get(url("pool" + i, "cc", "no-store"));
            allOk &= r.status == 200 && "MISS".equals(r.xcache());
        }
        check("20 sequential misses all succeed and reach the origin 20 times",
                allOk && origin.requestCount() - r0 == 20);
        check("...over a reused connection (origin accepted at most 3 connections, not 20)",
                origin.connectionCount() - c0 <= 3);

        // origin that answers "Connection: close": its connection must not be pooled
        c0 = origin.connectionCount();
        allOk = true;
        for (int i = 0; i < 3; i++) {
            Resp r = get(url("closing" + i, "cc", "no-store", "close", "1"));
            allOk &= r.status == 200 && !r.body.isEmpty();
        }
        check("origin saying Connection: close is respected (a new connection for each of the next requests)",
                allOk && origin.connectionCount() - c0 >= 2);

        // origin that announces keep-alive but hangs up: the stale pooled connection must be retried invisibly
        allOk = true;
        for (int i = 0; i < 4; i++) {
            Resp r = get(url("dropping" + i, "cc", "no-store", "drop", "1"));
            allOk &= r.status == 200 && !r.body.isEmpty();
        }
        check("a pooled connection the origin has silently closed is retried on a fresh one (no 502 for the client)", allOk);

        // origin speaking HTTP/1.0 with no length: the body ends when the origin closes
        ServerSocket old = new ServerSocket(0);
        int oldPort = old.getLocalPort();
        Thread ot = new Thread(() -> {
            try {
                while (true) {
                    Socket c = old.accept();
                    c.getInputStream().read(new byte[2048]);
                    c.getOutputStream().write("HTTP/1.0 200 OK\r\nCache-Control: no-store\r\n\r\nhello-eof"
                            .getBytes(StandardCharsets.ISO_8859_1));
                    c.close();
                }
            } catch (IOException e) {
                // closed
            }
        });
        ot.setDaemon(true);
        ot.start();
        Resp e1 = get("http://localhost:" + oldPort + "/a");
        Resp e2 = get("http://localhost:" + oldPort + "/b");
        check("an HTTP/1.0 origin whose body ends at connection close works, twice in a row",
                e1.status == 200 && "hello-eof".equals(e1.body) && "9".equals(e1.headers.get("content-length"))
                        && e2.status == 200 && "hello-eof".equals(e2.body));
        old.close();
    }

    static void keepAliveTests() throws Exception {
        System.out.println("Persistent connections (keep-alive)");
        {
            String ka = url("k1", "cc", "max-age=60", "etag", "v1");
            int o = origin.requestCount();
            try (Socket s = open()) {
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1"));
                Resp r1 = readOne(s.getInputStream(), false);
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1"));
                Resp r2 = readOne(s.getInputStream(), false);
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1"));
                Resp r3 = readOne(s.getInputStream(), false);
                check("three requests on ONE connection: MISS, HIT, HIT (origin contacted once)",
                        r1 != null && r2 != null && r3 != null && "MISS".equals(r1.xcache())
                                && "HIT".equals(r2.xcache()) && "HIT".equals(r3.xcache())
                                && origin.requestCount() - o == 1);
                check("responses announce Connection: keep-alive and the bodies are intact",
                        "keep-alive".equals(r2.headers.get("connection")) && r2.body.equals(r1.body)
                                && !r1.body.isEmpty());

                // mixed framing on the same connection: HEAD (no body), conditional (304), then a full GET
                s.getOutputStream().write(raw("HEAD", ka, "HTTP/1.1"));
                Resp head1 = readOne(s.getInputStream(), true);
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1", "If-None-Match: \"v1\""));
                Resp nm = readOne(s.getInputStream(), false);
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1"));
                Resp again = readOne(s.getInputStream(), false);
                check("HEAD, 304 and full GET share a connection without stray bytes",
                        head1 != null && head1.body.isEmpty() && nm != null && nm.status == 304
                                && again != null && again.status == 200 && again.body.equals(r1.body));
            }

            try (Socket s = open()) {
                // two requests sent back to back in one write (pipelining)
                byte[] one = raw("GET", ka, "HTTP/1.1");
                byte[] both = new byte[one.length * 2];
                System.arraycopy(one, 0, both, 0, one.length);
                System.arraycopy(one, 0, both, one.length, one.length);
                s.getOutputStream().write(both);
                Resp p1 = readOne(s.getInputStream(), false);
                Resp p2 = readOne(s.getInputStream(), false);
                check("pipelined requests are answered in order", p1 != null && p2 != null
                        && p1.status == 200 && p2.status == 200 && p1.body.equals(p2.body));
            }

            try (Socket s = open()) {
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1", "Connection: close"));
                Resp c = readOne(s.getInputStream(), false);
                check("Connection: close is honoured (response says close, then EOF)",
                        c != null && "close".equals(c.headers.get("connection")) && s.getInputStream().read() == -1);
            }
            try (Socket s = open()) {
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.0"));
                Resp c = readOne(s.getInputStream(), false);
                check("HTTP/1.0 without keep-alive is closed after one response",
                        c != null && "close".equals(c.headers.get("connection")) && s.getInputStream().read() == -1);
            }
            try (Socket s = open()) {
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.0", "Connection: keep-alive"));
                Resp c1 = readOne(s.getInputStream(), false);
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.0", "Connection: keep-alive"));
                Resp c2 = readOne(s.getInputStream(), false);
                check("HTTP/1.0 with Connection: keep-alive stays open",
                        c1 != null && c2 != null && "keep-alive".equals(c1.headers.get("connection")));
            }
            try (Socket s = open()) {
                s.getOutputStream().write(raw("GET", ka, "HTTP/1.1", "Content-Length: 5"));
                s.getOutputStream().write("hello".getBytes(StandardCharsets.ISO_8859_1));
                Resp c = readOne(s.getInputStream(), false);
                check("a request that carries a body is answered, then the connection is closed",
                        c != null && c.status == 200 && "close".equals(c.headers.get("connection")));
            }
            try (Socket s = open()) {
                s.getOutputStream().write(raw("POST", ka, "HTTP/1.1"));
                Resp e = readOne(s.getInputStream(), false);
                check("an error response (405) closes the connection",
                        e != null && e.status == 405 && s.getInputStream().read() == -1);
            }
            try (Socket s = open()) {
                // the 100th request on a connection is answered with Connection: close, like the team's server
                Resp last = null;
                boolean allKeepAlive = true;
                for (int i = 1; i <= 100; i++) {
                    s.getOutputStream().write(raw("GET", ka, "HTTP/1.1"));
                    last = readOne(s.getInputStream(), false);
                    if (last == null) {
                        break;
                    }
                    if (i < 100 && !"keep-alive".equals(last.headers.get("connection"))) {
                        allKeepAlive = false;
                    }
                }
                check("after 100 requests the proxy closes the connection",
                        last != null && allKeepAlive && "close".equals(last.headers.get("connection"))
                                && s.getInputStream().read() == -1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        ServerSocket originSocket = new ServerSocket(0);
        originPort = originSocket.getLocalPort();
        origin = new TestOrigin(originSocket, true);
        Thread ot = new Thread(origin::serve);
        ot.setDaemon(true);
        ot.start();

        ServerSocket proxySocket = new ServerSocket(0);
        proxyPort = proxySocket.getLocalPort();
        LruCache<String, CacheEntry> cache = new LruCache<>(100);
        Thread pt = new Thread(() -> ProxyServer.serve(proxySocket, cache));
        pt.setDaemon(true);
        pt.start();

        System.out.println("Response Cache-Control directives");
        twice("max-age=60 is cached", url("t1", "cc", "max-age=60"), "MISS", "HIT", 1);
        twice("no-store is never cached", url("t2", "cc", "no-store"), "MISS", "MISS", 2);
        twice("private is not cached by a shared proxy", url("t3", "cc", "private, max-age=60"), "MISS", "MISS", 2);
        twice("no-cache + ETag is stored but must be revalidated every time", url("t4", "cc", "no-cache", "etag", "v1"), "MISS", "REVALIDATED", 2);
        twice("s-maxage=60 beats max-age=0", url("t5", "cc", "max-age=0, s-maxage=60"), "MISS", "HIT", 1);
        twice("max-age=0 + ETag is revalidated, never served unchecked", url("t6", "cc", "max-age=0", "etag", "v1"), "MISS", "REVALIDATED", 2);
        twice("no headers at all: nothing to cache", url("t7"), "MISS", "MISS", 2);
        twice("Vary responses are not cached", url("t8", "cc", "max-age=60", "vary", "Accept-Encoding"), "MISS", "MISS", 2);
        twice("500 is not cached even with max-age", url("t9", "status", "500", "cc", "max-age=60"), "MISS", "MISS", 2);
        twice("404 without freshness info is not cached", url("t10", "status", "404"), "MISS", "MISS", 2);
        twice("404 with max-age is cached", url("t11", "status", "404", "cc", "max-age=60"), "MISS", "HIT", 1);

        System.out.println("Expires and heuristic freshness");
        twice("Expires in the future is cached", url("t12", "expires", "60"), "MISS", "HIT", 1);
        twice("Expires in the past is not cached", url("t13", "expires", "-60"), "MISS", "MISS", 2);
        twice("invalid Expires (\"0\") is treated as expired", url("t14", "expiresraw", "0"), "MISS", "MISS", 2);
        twice("heuristic freshness from old Last-Modified", url("t15", "lm", "360000"), "MISS", "HIT", 1);
        twice("max-age wins over a past Expires", url("t16", "cc", "max-age=60", "expires", "-60"), "MISS", "HIT", 1);

        System.out.println("Expiry over time");
        String u = url("t17", "cc", "max-age=1");
        int before = origin.requestCount();
        Resp a = get(u);
        Resp b = get(u);
        Thread.sleep(1200);
        Resp c = get(u);
        check("max-age=1: MISS, HIT, then MISS once it has expired",
                "MISS".equals(a.xcache()) && "HIT".equals(b.xcache()) && "MISS".equals(c.xcache())
                        && origin.requestCount() - before == 2);

        System.out.println("What a cache hit looks like");
        u = url("t18", "cc", "max-age=60", "cookie", "a=1");
        Resp first = get(u);
        Thread.sleep(1100);
        Resp hit = get(u);
        check("hit has the same body as the original response", first.body.equals(hit.body) && hit.status == 200);
        check("hit carries an Age header of at least 1", hit.headers.get("age") != null
                && Long.parseLong(hit.headers.get("age")) >= 1);
        check("miss has no Age header", first.headers.get("age") == null);
        check("end-to-end headers (Set-Cookie) survive caching", "a=1".equals(hit.headers.get("set-cookie")));
        Resp head = send(proxyPort, "HEAD", u);
        check("HEAD is answered from the cached GET, with no body",
                "HIT".equals(head.xcache()) && head.body.isEmpty()
                        && String.valueOf(first.body.length()).equals(head.headers.get("content-length")));

        System.out.println("Client Cache-Control request directives");
        u = url("t19", "cc", "max-age=60");
        get(u);
        before = origin.requestCount();
        Resp r1 = get(u, "Cache-Control: no-cache");
        Resp r2 = get(u);
        check("request no-cache bypasses the cache, and the refreshed copy is then served",
                "MISS".equals(r1.xcache()) && "HIT".equals(r2.xcache()) && origin.requestCount() - before == 1);
        before = origin.requestCount();
        Resp r3 = get(u, "Pragma: no-cache");
        check("request Pragma: no-cache bypasses the cache", "MISS".equals(r3.xcache()) && origin.requestCount() - before == 1);
        before = origin.requestCount();
        Resp r4 = get(u, "Cache-Control: max-age=0");
        check("request max-age=0 forces a refetch", "MISS".equals(r4.xcache()) && origin.requestCount() - before == 1);
        Thread.sleep(1100);
        Resp r5 = get(u, "Cache-Control: max-age=1");
        Resp r6 = get(u, "Cache-Control: max-age=30");
        check("request max-age=1 refuses a 1s-old entry; max-age=30 accepts a young one",
                "MISS".equals(r5.xcache()) && "HIT".equals(r6.xcache()));

        u = url("t20", "cc", "max-age=60");
        before = origin.requestCount();
        Resp o1 = get(u, "Cache-Control: only-if-cached");
        check("only-if-cached with nothing cached gives 504 and never contacts the origin",
                o1.status == 504 && origin.requestCount() == before);
        get(u);
        Resp o2 = get(u, "Cache-Control: only-if-cached");
        check("only-if-cached with a fresh entry is a HIT", o2.status == 200 && "HIT".equals(o2.xcache()));

        u = url("t21", "cc", "max-age=60");
        Resp n1 = get(u, "Cache-Control: no-store");
        Resp n2 = get(u);
        Resp n3 = get(u);
        check("request no-store: response not stored (MISS, MISS), later normal request caches (HIT)",
                "MISS".equals(n1.xcache()) && "MISS".equals(n2.xcache()) && "HIT".equals(n3.xcache()));

        System.out.println("Replacing and removing entries");
        u = url("t22", "cc", "max-age=60|no-store");
        Resp p1 = get(u);
        Resp p2 = get(u, "Cache-Control: no-cache"); // origin now answers no-store -> old copy must go
        Resp p3 = get(u);
        check("a later no-store response removes the old cached copy (MISS, MISS, MISS)",
                "MISS".equals(p1.xcache()) && "MISS".equals(p2.xcache()) && "MISS".equals(p3.xcache()));

        System.out.println("Authorization");
        u = url("t23", "cc", "max-age=60");
        int b4 = origin.requestCount();
        get(u, "Authorization: Bearer x");
        Resp q2 = get(u, "Authorization: Bearer x");
        check("response to an Authorization request is not cached unless public",
                "MISS".equals(q2.xcache()) && origin.requestCount() - b4 == 2);
        u = url("t24", "cc", "public, max-age=60");
        get(u, "Authorization: Bearer x");
        Resp q4 = get(u, "Authorization: Bearer x");
        check("public + Authorization is cached", "HIT".equals(q4.xcache()));

        System.out.println("Errors and framing");
        Resp bad = get("http://localhost:1/x");
        check("unreachable origin gives 502", bad.status == 502);
        Resp post = send(proxyPort, "POST", url("t25"));
        check("POST gives 405", post.status == 405);
        Resp big = get(url("t26", "cc", "max-age=60", "size", "200000"));
        Resp big2 = get(url("t26", "cc", "max-age=60", "size", "200000"));
        check("200 KB body is cached intact", "HIT".equals(big2.xcache()) && big2.body.length() == 200000 && big.body.length() == 200000);
        Resp huge1 = get(url("t27", "cc", "max-age=60", "size", "1100000"));
        Resp huge2 = get(url("t27", "cc", "max-age=60", "size", "1100000"));
        check("bodies over 1 MB are relayed but not cached", huge1.body.length() == 1100000 && "MISS".equals(huge2.xcache()));


        System.out.println("Revalidation with ETag (stale entry -> If-None-Match -> 304)");
        u = url("v1", "cc", "max-age=1", "etag", "v1");
        Resp m1 = get(u);
        Resp m2 = get(u);
        Thread.sleep(1200);
        int reqs0 = origin.requestCount();
        int cond0 = origin.conditionalCount();
        int nm0 = origin.notModifiedCount();
        Resp m3 = get(u);
        Resp m4 = get(u);
        check("expired entry with an ETag is revalidated and the client still gets a normal 200",
                "MISS".equals(m1.xcache()) && "HIT".equals(m2.xcache())
                        && "REVALIDATED".equals(m3.xcache()) && m3.status == 200);
        check("origin saw exactly one conditional request and answered 304",
                origin.requestCount() - reqs0 == 1 && origin.conditionalCount() - cond0 == 1
                        && origin.notModifiedCount() - nm0 == 1);
        check("the cached body is reused (not downloaded again)", m3.body.equals(m1.body));
        check("freshness restarts after revalidation (next request is a HIT)", "HIT".equals(m4.xcache()));

        u = url("v2", "cc", "max-age=1", "etag", "v1|v2");
        Resp c1 = get(u);
        Thread.sleep(1200);
        Resp c2 = get(u);
        Resp c3 = get(u);
        check("resource changed: origin answers a new 200 instead of 304 (MISS, new body, new ETag)",
                "MISS".equals(c2.xcache()) && c2.status == 200 && !c2.body.equals(c1.body)
                        && "\"v2\"".equals(c2.headers.get("etag")));
        check("the new version replaced the old one in the cache", "HIT".equals(c3.xcache()) && c3.body.equals(c2.body));

        u = url("v3", "cc", "max-age=1", "lm", "3600");
        get(u);
        Thread.sleep(1200);
        nm0 = origin.notModifiedCount();
        Resp l2 = get(u);
        check("revalidation also works with Last-Modified / If-Modified-Since",
                "REVALIDATED".equals(l2.xcache()) && origin.notModifiedCount() - nm0 == 1);

        u = url("v4", "cc", "max-age=1|max-age=60", "etag", "v1");
        get(u);
        Thread.sleep(1200);
        Resp h2 = get(u);
        Thread.sleep(1200);
        Resp h3 = get(u);
        check("headers of the 304 (new Cache-Control: max-age=60) replace the stored ones",
                "REVALIDATED".equals(h2.xcache()) && "HIT".equals(h3.xcache()));

        u = url("v5", "cc", "max-age=60", "etag", "v1");
        get(u);
        nm0 = origin.notModifiedCount();
        Resp nc = get(u, "Cache-Control: no-cache");
        check("client no-cache + stored ETag: origin is asked, body is not downloaded again",
                "REVALIDATED".equals(nc.xcache()) && origin.notModifiedCount() - nm0 == 1);

        u = url("v6", "cc", "max-age=1", "etag", "v1", "status", "200|500|200");
        Resp e1 = get(u);
        Thread.sleep(1200);
        Resp e2 = get(u);
        Resp e3 = get(u);
        check("origin error (500) during revalidation is passed on, and the stored copy is kept",
                e2.status == 500 && "MISS".equals(e2.xcache())
                        && "REVALIDATED".equals(e3.xcache()) && e3.status == 200 && e3.body.equals(e1.body));

        System.out.println("Client conditional requests (proxy answers 304 itself)");
        u = url("c1", "cc", "max-age=60", "etag", "v1", "lm", "3600");
        Resp full = get(u);
        String lastModified = full.headers.get("last-modified");
        int o0 = origin.requestCount();
        Resp a1 = get(u, "If-None-Match: \"v1\"");
        check("matching If-None-Match gives 304 from the cache: no body, origin not contacted",
                a1.status == 304 && "HIT".equals(a1.xcache()) && a1.body.isEmpty() && origin.requestCount() == o0);
        check("the 304 repeats ETag and Cache-Control and carries an Age header",
                "\"v1\"".equals(a1.headers.get("etag")) && "max-age=60".equals(a1.headers.get("cache-control"))
                        && a1.headers.get("age") != null);
        Resp a2 = get(u, "If-None-Match: \"other\"");
        check("non-matching If-None-Match gives the full 200", a2.status == 200 && a2.body.equals(full.body));
        check("weak form W/\"v1\" matches (weak comparison)", get(u, "If-None-Match: W/\"v1\"").status == 304);
        check("a list of ETags, and *, match", get(u, "If-None-Match: \"a\", \"v1\"").status == 304
                && get(u, "If-None-Match: *").status == 304);
        check("If-Modified-Since equal to Last-Modified gives 304",
                get(u, "If-Modified-Since: " + lastModified).status == 304);
        check("If-Modified-Since older than Last-Modified gives 200",
                get(u, "If-Modified-Since: Mon, 01 Jan 2001 00:00:00 GMT").status == 200);
        check("If-None-Match takes precedence over If-Modified-Since",
                get(u, "If-None-Match: \"other\"", "If-Modified-Since: " + lastModified).status == 200);
        check("an unparseable If-Modified-Since is ignored", get(u, "If-Modified-Since: yesterday").status == 200);
        check("HEAD with a matching validator gives 304 as well",
                send(proxyPort, "HEAD", u, "If-None-Match: \"v1\"").status == 304);

        u = url("c2", "cc", "max-age=60", "etag", "v1");
        cond0 = origin.conditionalCount();
        Resp mm = get(u, "If-None-Match: \"v1\"");
        check("client conditional on an uncached URL goes to the origin; its 304 is relayed",
                mm.status == 304 && "MISS".equals(mm.xcache()) && origin.conditionalCount() - cond0 == 1);
        Resp mm2 = get(u);
        Resp mm3 = get(u);
        check("a relayed 304 stores nothing; the next full response is cached (MISS, HIT)",
                "MISS".equals(mm2.xcache()) && "HIT".equals(mm3.xcache()));

        u = url("c3", "cc", "max-age=1", "etag", "v1");
        get(u);
        Thread.sleep(1200);
        Resp s1 = get(u, "If-None-Match: \"bogus\"");
        check("stale entry + client validator that does not match: proxy revalidates with ITS ETag, client gets the 200",
                "REVALIDATED".equals(s1.xcache()) && s1.status == 200 && !s1.body.isEmpty());
        Thread.sleep(1200);
        Resp s2 = get(u, "If-None-Match: \"v1\"");
        check("stale entry + matching client validator: revalidated, then 304 to the client",
                "REVALIDATED".equals(s2.xcache()) && s2.status == 304);

        u = url("w1", "cc", "max-age=60", "etag", "W/\"w1\"");
        get(u);
        check("a weak ETag in the cache matches a strong If-None-Match (weak comparison)",
                get(u, "If-None-Match: \"w1\"").status == 304);

        System.out.println("HEAD revalidation");
        u = url("h1", "cc", "max-age=1", "etag", "v1");
        get(u);
        Thread.sleep(1200);
        Resp hd = send(proxyPort, "HEAD", u);
        Resp hg = get(u);
        check("HEAD on a stale entry revalidates (no body) and refreshes it for the next GET",
                "REVALIDATED".equals(hd.xcache()) && hd.body.isEmpty() && "HIT".equals(hg.xcache()) && !hg.body.isEmpty());
        u = url("h2", "cc", "max-age=1", "etag", "v1|v2");
        get(u);
        Thread.sleep(1200);
        Resp hd2 = send(proxyPort, "HEAD", u);
        Resp hg2 = get(u);
        check("HEAD that reveals a change drops the old copy; next GET fetches the new version",
                "MISS".equals(hd2.xcache()) && "MISS".equals(hg2.xcache()) && "\"v2\"".equals(hg2.headers.get("etag")));


        keepAliveTests();

        System.out.println("Connections from the proxy to the origin");
        originConnectionTests();

        System.out.println("LRU eviction through the proxy (capacity 2)");
        ServerSocket smallSocket = new ServerSocket(0);
        int smallPort = smallSocket.getLocalPort();
        Thread st = new Thread(() -> ProxyServer.serve(smallSocket, new LruCache<>(2)));
        st.setDaemon(true);
        st.start();
        String ua = url("ev-a", "cc", "max-age=60");
        String ub = url("ev-b", "cc", "max-age=60");
        String uc = url("ev-c", "cc", "max-age=60");
        send(smallPort, "GET", ua);
        send(smallPort, "GET", ub);
        send(smallPort, "GET", uc);                   // evicts a
        Resp ea = send(smallPort, "GET", ua);         // MISS, evicts b
        Resp ec = send(smallPort, "GET", uc);         // still cached
        check("least recently used entry is evicted when the cache is full",
                "MISS".equals(ea.xcache()) && "HIT".equals(ec.xcache()));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.out.println("Proxy stats: " + ProxyStats.summary());
        System.exit(failed == 0 ? 0 : 1);
    }
}

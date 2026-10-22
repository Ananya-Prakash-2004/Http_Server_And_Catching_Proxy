package httpserver;

import java.io.IOException;
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
            StringBuilder req = new StringBuilder(method + " " + url + " HTTP/1.1\r\nHost: test\r\n");
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
        twice("no-cache + ETag is stored but never served unchecked", url("t4", "cc", "no-cache", "etag", "v1"), "MISS", "MISS", 2);
        twice("s-maxage=60 beats max-age=0", url("t5", "cc", "max-age=0, s-maxage=60"), "MISS", "HIT", 1);
        twice("max-age=0 is not served from cache", url("t6", "cc", "max-age=0", "etag", "v1"), "MISS", "MISS", 2);
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

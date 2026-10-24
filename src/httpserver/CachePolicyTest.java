package httpserver;

import java.util.ArrayList;
import java.util.List;

/** Unit tests for CacheControl parsing and CachePolicy, using a fixed clock. Run: java -cp out httpserver.CachePolicyTest */
public class CachePolicyTest {
    static final long NOW = 1_800_000_000_000L; // fixed "current time" in ms

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
        System.out.println("  ok - " + message);
    }

    static void check(String message, boolean condition) {
        check(condition, message);
    }

    static ProxyResponse resp(int status, String... headerPairs) {
        List<String[]> h = new ArrayList<>();
        for (int i = 0; i < headerPairs.length; i += 2) {
            h.add(new String[]{headerPairs[i], headerPairs[i + 1]});
        }
        return new ProxyResponse(status, "X", h, "body".getBytes(), 4);
    }

    static CacheEntry eval(ProxyResponse r) {
        return CachePolicy.evaluate(r, false, NOW);
    }

    public static void main(String[] args) {
        System.out.println("CacheControl parsing");
        CacheControl cc = CacheControl.parse("Public, MAX-AGE=60, s-maxage=\"30\", private=\"set-cookie, x\"");
        check(cc.has("public") && cc.has("max-age"), "directive names are case-insensitive");
        check(cc.seconds("max-age") == 60, "max-age=60");
        check(cc.seconds("s-maxage") == 30, "quoted value is unquoted");
        check(cc.has("private"), "qualified private (with a comma inside quotes) parsed as one directive");
        check(cc.seconds("must-revalidate") == -1, "absent directive gives -1");
        check(CacheControl.parse("max-age=abc").seconds("max-age") == 0, "invalid max-age counts as 0 (stale)");
        check(CacheControl.parse("max-age=-5").seconds("max-age") == 0, "negative max-age counts as 0");
        check(CacheControl.parse("max-age=99999999999999999999").seconds("max-age") == 2147483648L, "overflow is capped");
        check(!CacheControl.parse(null).has("no-store"), "null header is fine");
        check(CacheControl.parse("max-age=10, max-age=99").seconds("max-age") == 10, "first duplicate wins");

        System.out.println("Storability");
        check(eval(resp(200, "Cache-Control", "max-age=60")) != null, "200 + max-age is stored");
        check(eval(resp(200, "Cache-Control", "no-store, max-age=60")) == null, "no-store is never stored");
        check(eval(resp(200, "Cache-Control", "private, max-age=60")) == null, "private is not stored by a shared cache");
        check(eval(resp(200, "Cache-Control", "max-age=60", "Vary", "Accept-Encoding")) == null, "Vary responses are not stored");
        check(eval(resp(500, "Cache-Control", "max-age=60")) == null, "500 is not stored");
        check(eval(resp(404, "Cache-Control", "max-age=60")) != null, "404 with max-age is stored (negative caching)");
        check(eval(resp(200)) == null, "no freshness info and no validator: not worth storing");
        check(eval(resp(200, "ETag", "\"v1\"")) != null, "no freshness but has ETag: stored for revalidation");
        check(CachePolicy.evaluate(resp(200, "Cache-Control", "max-age=60"), true, NOW) == null,
                "request had Authorization and response is not public: not stored");
        check(CachePolicy.evaluate(resp(200, "Cache-Control", "public, max-age=60"), true, NOW) != null,
                "Authorization + public is stored");
        check(CachePolicy.evaluate(resp(200, "Cache-Control", "s-maxage=60"), true, NOW) != null,
                "Authorization + s-maxage is stored");

        System.out.println("Freshness lifetime");
        CacheEntry e = eval(resp(200, "Cache-Control", "max-age=60"));
        check(e.isFresh(NOW, -1) && e.isFresh(NOW + 59_000, -1), "fresh before max-age");
        check(!e.isFresh(NOW + 60_000, -1), "stale exactly at max-age");
        check(e.currentAgeSeconds(NOW + 25_000) == 25, "age counts seconds since stored");
        e = eval(resp(200, "Cache-Control", "max-age=1000, s-maxage=10"));
        check(e.isFresh(NOW + 9_000, -1) && !e.isFresh(NOW + 10_000, -1), "s-maxage beats max-age in a shared cache");
        e = eval(resp(200, "Cache-Control", "max-age=0", "ETag", "\"x\""));
        check(!e.isFresh(NOW, -1), "max-age=0 is never fresh");
        e = eval(resp(200, "Cache-Control", "no-cache, max-age=600", "ETag", "\"x\""));
        check(!e.isFresh(NOW, -1), "no-cache means always revalidate");
        e = eval(resp(200, "Date", HttpDates.format(NOW), "Expires", HttpDates.format(NOW + 120_000)));
        check(e.isFresh(NOW + 119_000, -1) && !e.isFresh(NOW + 120_000, -1), "Expires - Date gives the lifetime");
        check(eval(resp(200, "Expires", "0")) == null, "invalid Expires (\"0\") means already expired");
        e = eval(resp(200, "Cache-Control", "max-age=10", "Expires", HttpDates.format(NOW + 999_000)));
        check(!e.isFresh(NOW + 10_000, -1), "max-age beats Expires");
        e = eval(resp(200, "Date", HttpDates.format(NOW), "Last-Modified", HttpDates.format(NOW - 100_000_000L)));
        check(e.isFresh(NOW + 9_000_000L, -1) && !e.isFresh(NOW + 10_000_000L, -1), "heuristic = 10% of time since Last-Modified");
        e = eval(resp(200, "Date", HttpDates.format(NOW), "Last-Modified", HttpDates.format(NOW - 100L * 86_400_000L)));
        check(e.getFreshnessLifetimeSeconds() == 86_400, "heuristic is capped at 24 hours");
        e = eval(resp(200, "Cache-Control", "max-age=60", "Age", "50"));
        check(e.isFresh(NOW + 9_000, -1) && !e.isFresh(NOW + 10_000, -1), "Age header from an upstream cache counts toward age");

        System.out.println("Client max-age");
        e = eval(resp(200, "Cache-Control", "max-age=600"));
        check(e.isFresh(NOW + 5_000, 10), "entry younger than the client's max-age is acceptable");
        check(!e.isFresh(NOW + 15_000, 10), "entry older than the client's max-age is refused");
        check(!e.isFresh(NOW, 0), "client max-age=0 always forces a refetch");


        System.out.println("Client conditional matching");
        ProxyResponse v = resp(200, "ETag", "\"v1\"", "Last-Modified", HttpDates.format(NOW - 5_000_000L));
        check(CachePolicy.clientConditionMatches("\"v1\"", null, v), "If-None-Match with the same ETag matches");
        check(!CachePolicy.clientConditionMatches("\"v2\"", null, v), "different ETag does not match");
        check(CachePolicy.clientConditionMatches("W/\"v1\"", null, v), "weak comparison: W/\"v1\" matches \"v1\"");
        check(CachePolicy.clientConditionMatches("\"a\", \"b\", \"v1\"", null, v), "ETag list is searched");
        check(CachePolicy.clientConditionMatches("*", null, v), "* matches any existing representation");
        check(CachePolicy.clientConditionMatches("\"x,y\", \"v1\"", null, v), "a comma inside a quoted ETag does not break the list");
        check(CachePolicy.clientConditionMatches("\"x,v1\"", null, resp(200, "ETag", "\"x,v1\"")), "ETag containing a comma matches itself");
        check(!CachePolicy.clientConditionMatches("\"v1\"", null, resp(200)), "no ETag on our side: no match");
        check(CachePolicy.clientConditionMatches(null, HttpDates.format(NOW), v), "If-Modified-Since after Last-Modified matches");
        check(CachePolicy.clientConditionMatches(null, HttpDates.format(NOW - 5_000_000L), v), "If-Modified-Since equal to Last-Modified matches");
        check(!CachePolicy.clientConditionMatches(null, HttpDates.format(NOW - 6_000_000L), v), "older If-Modified-Since does not match");
        check(!CachePolicy.clientConditionMatches(null, "garbage", v), "unparseable date is ignored");
        check(!CachePolicy.clientConditionMatches("\"v2\"", HttpDates.format(NOW), v), "If-None-Match wins over If-Modified-Since");
        check(!CachePolicy.clientConditionMatches("\"v1\"", null, resp(404, "ETag", "\"v1\"")), "only a 200 can become a 304");

        System.out.println("Building a 304 and merging one into a stored response");
        ProxyResponse stored = new ProxyResponse(200, "OK", List.of(
                new String[]{"Content-Type", "text/plain"}, new String[]{"ETag", "\"v1\""},
                new String[]{"Cache-Control", "max-age=1"}, new String[]{"Age", "40"},
                new String[]{"X-Custom", "keep me"}), "hello".getBytes(), 5);
        ProxyResponse nm = new ProxyResponse(304, "Not Modified", List.of(
                new String[]{"Cache-Control", "max-age=60"}, new String[]{"Date", "Mon, 01 Jan 2029 00:00:00 GMT"}),
                new byte[0], -1);
        ProxyResponse merged = stored.withHeadersFrom(nm);
        check("304's Cache-Control replaces the stored one", "max-age=60".equals(merged.getHeader("cache-control")));
        check("headers the 304 does not mention are kept", "keep me".equals(merged.getHeader("x-custom"))
                && "\"v1\"".equals(merged.getHeader("etag")));
        check("the stored Age is dropped", merged.getHeader("age") == null);
        check("status and body stay those of the stored response", merged.getStatusCode() == 200 && merged.bodySize() == 5);
        ProxyResponse n304 = ProxyResponse.notModifiedFrom(stored);
        check("generated 304 has status 304 and no body", n304.getStatusCode() == 304 && n304.bodySize() == 0);
        check("generated 304 keeps ETag and Cache-Control, drops Content-Type",
                n304.getHeader("etag") != null && n304.getHeader("cache-control") != null && n304.getHeader("content-type") == null);
        String wire = new String(n304.toBytes(true, "HIT", 7), java.nio.charset.StandardCharsets.ISO_8859_1);
        check("304 on the wire has Age and no Content-Length",
                wire.startsWith("HTTP/1.1 304 Not Modified") && wire.contains("Age: 7") && !wire.contains("Content-Length"));

        System.out.println("\nAll CachePolicy tests passed.");
    }
}

package httpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public final class CachePolicy {

   
    private static final Set<Integer> CACHEABLE_STATUS = Set.of(200, 203, 204, 300, 301, 404, 405, 410, 414, 501);

    private static final long HEURISTIC_CAP_MS = 24L * 60 * 60 * 1000;

    private CachePolicy() {
    }

    
    public static CacheEntry evaluate(ProxyResponse resp, boolean requestHadAuthorization, long nowMs) {
        CacheControl cc = CacheControl.parse(resp.getHeaderJoined("cache-control"));

        if (!CACHEABLE_STATUS.contains(resp.getStatusCode())) {
            return null;
        }
        if (cc.has("no-store") || cc.has("private")) {
            return null; 
        }
        if (resp.getHeader("vary") != null) {
            return null; 
        }
        if (requestHadAuthorization
                && !(cc.has("public") || cc.has("s-maxage") || cc.has("must-revalidate"))) {
            return null; 
        }

        boolean noCache = cc.has("no-cache");
        long lifetimeMs = noCache ? 0 : freshnessLifetimeMs(resp, cc, nowMs);

        String etag = resp.getHeader("etag");
        String lastModified = resp.getHeader("last-modified");
        boolean canRevalidate = etag != null || lastModified != null;

        if (lifetimeMs <= 0 && !canRevalidate) {
            return null; 
        }

        long initialAge = 0;
        String ageHeader = resp.getHeader("age");
        if (ageHeader != null) {
            try {
                initialAge = Math.max(0, Long.parseLong(ageHeader.trim()));
            } catch (NumberFormatException ignored) {
                // keep 0
            }
        }
        return new CacheEntry(resp, nowMs, lifetimeMs, initialAge, etag, lastModified, noCache);
    }

    
    static long freshnessLifetimeMs(ProxyResponse resp, CacheControl cc, long nowMs) {
        long sMaxAge = cc.seconds("s-maxage"); // applies to shared caches and beats max-age
        if (sMaxAge >= 0) {
            return sMaxAge * 1000;
        }
        long maxAge = cc.seconds("max-age");
        if (maxAge >= 0) {
            return maxAge * 1000;
        }

        Long date = HttpDates.parse(resp.getHeader("date"));
        long base = date != null ? date : nowMs;

        String expiresText = resp.getHeader("expires");
        if (expiresText != null) {
            Long expires = HttpDates.parse(expiresText);
            if (expires == null) {
                return 0; 
            }
            return Math.max(0, expires - base);
        }

        
        Long lastModified = HttpDates.parse(resp.getHeader("last-modified"));
        if (lastModified != null && base > lastModified) {
            return Math.min(HEURISTIC_CAP_MS, (base - lastModified) / 10);
        }
        return 0;
    }

    
    public static boolean clientConditionMatches(String ifNoneMatch, String ifModifiedSince, ProxyResponse resp) {
        if (resp.getStatusCode() != 200) {
            return false; // conditionals only turn a 200 into a 304
        }
        if (ifNoneMatch != null) {
            if (ifNoneMatch.trim().equals("*")) {
                return true;
            }
            String etag = resp.getHeader("etag");
            if (etag == null) {
                return false;
            }
            String mine = opaqueTag(etag);
            for (String tag : parseEtagList(ifNoneMatch)) {
                if (tag.equals(mine)) {
                    return true;
                }
            }
            return false;
        }
        if (ifModifiedSince != null) {
            Long since = HttpDates.parse(ifModifiedSince);
            Long modified = HttpDates.parse(resp.getHeader("last-modified"));
            return since != null && modified != null && modified <= since;
        }
        return false;
    }

    
    static String opaqueTag(String etag) {
        String t = etag.trim();
        return t.startsWith("W/") ? t.substring(2) : t;
    }

    
    static List<String> parseEtagList(String value) {
        List<String> tags = new ArrayList<>();
        int i = 0;
        int n = value.length();
        while (i < n) {
            char c = value.charAt(i);
            if (c == ',' || c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (value.startsWith("W/", i)) {
                i += 2;
            }
            if (i < n && value.charAt(i) == '"') {
                int end = value.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                tags.add(value.substring(i, end + 1));
                i = end + 1;
            } else {
                int comma = value.indexOf(',', i);
                if (comma < 0) {
                    break;
                }
                i = comma + 1; 
            }
        }
        return tags;
    }
}

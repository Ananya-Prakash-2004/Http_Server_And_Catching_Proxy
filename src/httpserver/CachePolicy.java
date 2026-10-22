package httpserver;

import java.util.Set;

/**
 * The caching rules of a shared cache (RFC 7234), kept separate from the socket
 * code so they can be unit tested with a fixed clock.
 *
 * Deliberate simplifications (all on the safe side): a response that carries a
 * Vary header is never stored; field-qualified directives such as private="x" are
 * treated as plain "private"; only the IMF-fixdate HTTP-date format is understood
 * (anything else counts as already expired).
 */
public final class CachePolicy {

    /** Statuses a cache may store by default (RFC 7231 section 6.1). */
    private static final Set<Integer> CACHEABLE_STATUS = Set.of(200, 203, 204, 300, 301, 404, 405, 410, 414, 501);

    private static final long HEURISTIC_CAP_MS = 24L * 60 * 60 * 1000;

    private CachePolicy() {
    }

    /**
     * Decides whether a response to a GET may be stored.
     *
     * @return a CacheEntry ready to store, or null if the response must not (or
     *         pointlessly could not) be stored
     */
    public static CacheEntry evaluate(ProxyResponse resp, boolean requestHadAuthorization, long nowMs) {
        CacheControl cc = CacheControl.parse(resp.getHeaderJoined("cache-control"));

        if (!CACHEABLE_STATUS.contains(resp.getStatusCode())) {
            return null;
        }
        if (cc.has("no-store") || cc.has("private")) {
            return null; // private = for one user only; a shared proxy must not keep it
        }
        if (resp.getHeader("vary") != null) {
            return null; // we do not keep per-variant copies
        }
        if (requestHadAuthorization
                && !(cc.has("public") || cc.has("s-maxage") || cc.has("must-revalidate"))) {
            return null; // RFC 7234 3.2: shared caches need explicit permission for these
        }

        boolean noCache = cc.has("no-cache");
        long lifetimeMs = noCache ? 0 : freshnessLifetimeMs(resp, cc, nowMs);

        String etag = resp.getHeader("etag");
        String lastModified = resp.getHeader("last-modified");
        boolean canRevalidate = etag != null || lastModified != null;

        if (lifetimeMs <= 0 && !canRevalidate) {
            return null; // stale on arrival and cannot be validated: storing it is useless
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

    /** How long (ms) the response stays fresh. Order: s-maxage, max-age, Expires, heuristic. */
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
                return 0; // "0", "-1" or garbage means already expired
            }
            return Math.max(0, expires - base);
        }

        // Heuristic: 10% of the time since the resource last changed, capped at 24h.
        Long lastModified = HttpDates.parse(resp.getHeader("last-modified"));
        if (lastModified != null && base > lastModified) {
            return Math.min(HEURISTIC_CAP_MS, (base - lastModified) / 10);
        }
        return 0;
    }
}

package httpserver;

/**
 * A cached response plus the bookkeeping needed to decide whether it is still
 * fresh. Immutable. The validators (ETag / Last-Modified) are kept for Step 4's
 * conditional requests.
 */
public final class CacheEntry {
    private final ProxyResponse response;
    private final long storedAtMs;
    private final long freshnessLifetimeMs;
    private final long initialAgeSeconds;   // from an upstream cache's Age header, usually 0
    private final String etag;              // may be null
    private final String lastModified;      // may be null
    private final boolean mustRevalidate;   // response said "no-cache": never serve without checking

    public CacheEntry(ProxyResponse response, long storedAtMs, long freshnessLifetimeMs,
                      long initialAgeSeconds, String etag, String lastModified, boolean mustRevalidate) {
        this.response = response;
        this.storedAtMs = storedAtMs;
        this.freshnessLifetimeMs = freshnessLifetimeMs;
        this.initialAgeSeconds = initialAgeSeconds;
        this.etag = etag;
        this.lastModified = lastModified;
        this.mustRevalidate = mustRevalidate;
    }

    public ProxyResponse getResponse() {
        return response;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public long getFreshnessLifetimeSeconds() {
        return freshnessLifetimeMs / 1000;
    }

    /** Age in whole seconds: time already spent upstream plus time spent in this cache. */
    public long currentAgeSeconds(long nowMs) {
        return initialAgeSeconds + Math.max(0, nowMs - storedAtMs) / 1000;
    }

    /**
     * True if this entry may be served without contacting the origin.
     *
     * @param requestMaxAgeSeconds the client's "Cache-Control: max-age=N", or -1 if none.
     *                             The entry must be strictly younger than N seconds, so
     *                             max-age=0 always forces a trip to the origin.
     */
    public boolean isFresh(long nowMs, long requestMaxAgeSeconds) {
        if (mustRevalidate) {
            return false;
        }
        long ageMs = initialAgeSeconds * 1000 + Math.max(0, nowMs - storedAtMs);
        if (ageMs >= freshnessLifetimeMs) {
            return false;
        }
        return requestMaxAgeSeconds < 0 || ageMs < requestMaxAgeSeconds * 1000;
    }
}

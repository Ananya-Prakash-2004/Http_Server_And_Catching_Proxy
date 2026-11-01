package httpserver;

public final class CacheEntry {
    private final ProxyResponse response;
    private final long storedAtMs;
    private final long freshnessLifetimeMs;
    private final long initialAgeSeconds;   
    private final String etag;              
    private final String lastModified;      
    private final boolean mustRevalidate;   

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

    
    public long currentAgeSeconds(long nowMs) {
        return initialAgeSeconds + Math.max(0, nowMs - storedAtMs) / 1000;
    }

    
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

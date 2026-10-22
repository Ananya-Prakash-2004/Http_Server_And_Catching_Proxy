package httpserver;

import java.util.concurrent.atomic.AtomicLong;

/** Server-side counters of how each proxied GET/HEAD was answered. */
public final class ProxyStats {
    public static final AtomicLong HITS = new AtomicLong();    // served from cache, origin not contacted
    public static final AtomicLong MISSES = new AtomicLong();  // nothing cached for the URL
    public static final AtomicLong STALE = new AtomicLong();   // cached but expired, refetched
    public static final AtomicLong BYPASS = new AtomicLong();  // client asked for a fresh copy (no-cache)

    private ProxyStats() {
    }

    public static String summary() {
        long h = HITS.get();
        long total = h + MISSES.get() + STALE.get() + BYPASS.get();
        double ratio = total == 0 ? 0 : 100.0 * h / total;
        return String.format("requests=%d hits=%d misses=%d stale=%d bypass=%d hit-ratio=%.1f%%",
                total, h, MISSES.get(), STALE.get(), BYPASS.get(), ratio);
    }
}

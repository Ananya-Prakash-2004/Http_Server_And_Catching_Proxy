package httpserver;

import java.util.concurrent.atomic.AtomicLong;

/** Server-side counters of how each proxied request was answered. Errors (502/504) are not counted. */
public final class ProxyStats {
    public static final AtomicLong HITS = new AtomicLong();          // fresh copy served, origin not contacted
    public static final AtomicLong REVALIDATED = new AtomicLong();   // stale copy checked with the origin (304), body reused
    public static final AtomicLong MISSES = new AtomicLong();        // full response came from the origin
    public static final AtomicLong NOT_MODIFIED_SENT = new AtomicLong(); // 304s the proxy sent to clients from its own knowledge

    private ProxyStats() {
    }

    public static String summary() {
        long h = HITS.get();
        long r = REVALIDATED.get();
        long total = h + r + MISSES.get();
        double hitRatio = total == 0 ? 0 : 100.0 * h / total;
        double savedBody = total == 0 ? 0 : 100.0 * (h + r) / total;
        return String.format("requests=%d hits=%d revalidated=%d misses=%d 304-sent-to-clients=%d"
                        + " hit-ratio=%.1f%% (%.1f%% if revalidations count)",
                total, h, r, MISSES.get(), NOT_MODIFIED_SENT.get(), hitRatio, savedBody);
    }
}

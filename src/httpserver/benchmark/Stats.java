package httpserver.benchmark;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class Stats {
    private final long[] latencies;      // nanoseconds, one slot per successful request
    private final AtomicLong count = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    public final AtomicLong rejected503 = new AtomicLong();

    public Stats(int maxSamples) {
        this.latencies = new long[maxSamples];
    }

    public void recordSuccess(long nanos) {
        long i = count.getAndIncrement();
        if (i < latencies.length) latencies[(int) i] = nanos;
    }

    public long successCount() {
        return Math.min(count.get(), latencies.length);
    }

    // p in [0,100]; returns milliseconds
    public double percentileMs(double p) {
        int n = (int) successCount();
        if (n == 0) return 0;
        long[] copy = Arrays.copyOf(latencies, n);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        return copy[Math.max(0, Math.min(idx, n - 1))] / 1_000_000.0;
    }

    public double requestsPerSec(double windowSeconds) {
        return successCount() / windowSeconds;
    }
}
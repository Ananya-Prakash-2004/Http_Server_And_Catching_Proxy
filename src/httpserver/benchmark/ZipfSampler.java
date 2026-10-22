package httpserver.benchmark;

import java.util.Arrays;
import java.util.Random;

/**
 * Zipf-distributed popularity: the object of rank k (1 = most popular) is requested with
 * probability proportional to 1 / k^s. s = 0 is uniform; web traffic is usually s of about 0.6 to 1.2.
 */
public final class ZipfSampler {
    private final double[] cdf;       // cumulative probability, cdf[n-1] == 1.0
    private final double[] probability;

    public ZipfSampler(int n, double s) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        probability = new double[n];
        double sum = 0;
        for (int k = 1; k <= n; k++) {
            probability[k - 1] = 1.0 / Math.pow(k, s);
            sum += probability[k - 1];
        }
        cdf = new double[n];
        double running = 0;
        for (int i = 0; i < n; i++) {
            probability[i] /= sum;
            running += probability[i];
            cdf[i] = running;
        }
        cdf[n - 1] = 1.0; // guard against rounding
    }

    /** Draws an object index in [0, n); index 0 is the most popular object. */
    public int sample(Random random) {
        int idx = Arrays.binarySearch(cdf, random.nextDouble());
        if (idx < 0) {
            idx = -idx - 1;
        }
        return Math.min(idx, cdf.length - 1);
    }

    /** A reproducible request sequence, so every configuration is tested with identical traffic. */
    public int[] sequence(int count, long seed) {
        Random random = new Random(seed);
        int[] seq = new int[count];
        for (int i = 0; i < count; i++) {
            seq[i] = sample(random);
        }
        return seq;
    }

    /** Share of all requests that go to the {@code topK} most popular objects. */
    public double topMass(int topK) {
        double sum = 0;
        for (int i = 0; i < Math.min(topK, probability.length); i++) {
            sum += probability[i];
        }
        return sum;
    }
}

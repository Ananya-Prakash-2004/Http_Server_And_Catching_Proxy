package httpserver;

import java.util.ArrayList;
import java.util.List;

/** Plain-Java tests for LruCache (no JUnit needed). Run: java -cp out httpserver.LruCacheTest */
public class LruCacheTest {

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FAILED: " + message);
        }
        System.out.println("  ok - " + message);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Basic put/get");
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        check(c.get("a") == 1 && c.get("b") == 2, "values come back");
        check(c.get("zzz") == null, "missing key returns null");

        System.out.println("Eviction order");
        c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.put("c", 3); // evicts a (least recently used)
        check(c.get("a") == null, "oldest entry evicted");
        check(c.get("b") == 2 && c.get("c") == 3, "newer entries kept");
        check(c.size() == 2, "size stays at capacity");

        System.out.println("get() refreshes recency");
        c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.get("a");    // a is now most recent, b is LRU
        c.put("c", 3); // evicts b
        check(c.get("b") == null, "b (not a) was evicted");
        check(c.get("a") == 1, "a survived because it was read");

        System.out.println("put() on existing key");
        c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.put("a", 10); // update + refresh, no growth
        c.put("c", 3);  // evicts b
        check(c.size() == 2, "update does not grow the cache");
        check(c.get("a") == 10, "value updated");
        check(c.get("b") == null, "b evicted after a was refreshed");

        System.out.println("Capacity 1 and remove/clear");
        c = new LruCache<>(1);
        c.put("a", 1);
        c.put("b", 2);
        check(c.get("a") == null && c.get("b") == 2, "capacity 1 keeps only the newest");
        check(c.remove("b") == 2 && c.size() == 0, "remove works");
        check(c.remove("b") == null, "removing twice is harmless");
        c.put("x", 1);
        c.clear();
        check(c.size() == 0 && c.get("x") == null, "clear empties the cache");

        System.out.println("Concurrent stress (8 threads x 50,000 ops)");
        final LruCache<Integer, Integer> shared = new LruCache<>(50);
        List<Thread> threads = new ArrayList<>();
        final boolean[] failed = {false};
        for (int t = 0; t < 8; t++) {
            final int seed = t;
            Thread th = new Thread(() -> {
                java.util.Random r = new java.util.Random(seed);
                for (int i = 0; i < 50_000; i++) {
                    int k = r.nextInt(200);
                    if (r.nextBoolean()) {
                        shared.put(k, k);
                    } else {
                        Integer v = shared.get(k);
                        if (v != null && v != k) {
                            failed[0] = true;
                        }
                    }
                    if (shared.size() > 50) {
                        failed[0] = true;
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        check(!failed[0], "no corruption, size never above capacity");
        System.out.println("Stats: " + shared.stats());
        System.out.println("\nAll LruCache tests passed.");
    }
}
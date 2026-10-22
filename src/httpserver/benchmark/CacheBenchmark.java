package httpserver.benchmark;

import httpserver.CacheEntry;
import httpserver.LruCache;
import httpserver.ProxyHandler;
import httpserver.ProxyServer;
import httpserver.TestOrigin;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures what the proxy's cache buys under a realistic (Zipfian) request mix.
 *
 * Everything runs inside one JVM: a TestOrigin whose every reply takes {@code delay} ms (a
 * stand-in for a distant server), the proxy with an LRU cache of a given size, and a pool of
 * client threads that request objects drawn from a Zipf distribution. Each setting is run
 * with the SAME request sequence, and compared with a "no cache" baseline in which the
 * clients talk to the origin directly.
 *
 * Three sweeps:
 *   capacity  how big the cache is            (hit ratio rises with size, then flattens)
 *   skew      how concentrated popularity is  (more skew means more hits for the same cache)
 *   ttl       how long responses stay fresh   (short ttl turns hits into cheap revalidations)
 *
 * Usage: java -cp out httpserver.benchmark.CacheBenchmark [quick] [objects=N] [requests=N]
 *        [warmup=N] [concurrency=N] [delay=MS] [size=BYTES] [think=MS] [pause=SECONDS] [seed=N] [out=FILE]
 */
public final class CacheBenchmark {

    // ---- settings (override on the command line as key=value) ----
    private int objects = 500;
    private int requests = 10_000;     // measured requests per run
    private int warmup = 2_000;        // requests that fill the cache first and are not measured
    private int concurrency = 16;
    private int delayMs = 20;          // origin think time for every reply (also for 304s)
    private int bodyBytes = 2048;
    private int thinkMs = 20;          // pause between a client's requests in the ttl sweep, so runs last long enough to expire entries
    private int pauseSec = 3;          // pause between runs so sockets can drain
    private long seed = 42;
    private String outFile = "cache_results.csv";

    private static final byte ERROR = 0, HIT = 1, REVALIDATED = 2, MISS = 3;

    private TestOrigin origin;
    private int originPort;
    private final Map<Double, int[]> sequences = new HashMap<>();

    private static final class Result {
        String sweep;
        int capacity;          // 0 = no cache (clients talk to the origin directly)
        double zipfS;
        int ttl;
        int measured;
        int hit, revalidated, miss, errors;
        double meanMs, p50, p95, p99, hitP50, missP50, reqPerSec, idealHitPct, savingPct;
        int originRequests;
    }

    public static void main(String[] args) throws Exception {
        CacheBenchmark b = new CacheBenchmark();
        for (String a : args) {
            if (a.equals("quick")) {
                b.requests = 3_000;
                b.warmup = 500;
                b.objects = 300;
                b.pauseSec = 1;
            }
        }
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String k = a.substring(0, eq);
            String v = a.substring(eq + 1);
            switch (k) {
                case "objects": b.objects = Integer.parseInt(v); break;
                case "requests": b.requests = Integer.parseInt(v); break;
                case "warmup": b.warmup = Integer.parseInt(v); break;
                case "concurrency": b.concurrency = Integer.parseInt(v); break;
                case "delay": b.delayMs = Integer.parseInt(v); break;
                case "size": b.bodyBytes = Integer.parseInt(v); break;
                case "think": b.thinkMs = Integer.parseInt(v); break;
                case "pause": b.pauseSec = Integer.parseInt(v); break;
                case "seed": b.seed = Long.parseLong(v); break;
                case "out": b.outFile = v; break;
                default: System.err.println("Ignoring unknown option " + a);
            }
        }
        b.runAll();
        System.exit(0); // the in-process proxy and origin use non-daemon pool threads
    }

    private void runAll() throws Exception {
        ProxyHandler.verbose = false; // thousands of per-request log lines would only slow the proxy down
        ServerSocket originSocket = new ServerSocket(0);
        originPort = originSocket.getLocalPort();
        origin = new TestOrigin(originSocket, true);
        Thread ot = new Thread(origin::serve);
        ot.setDaemon(true);
        ot.start();

        System.out.printf("Settings: %d objects, %d measured requests (+%d warmup) per run, %d client threads,"
                        + " origin delay %d ms, %d-byte bodies, seed %d%n%n",
                objects, requests, warmup, concurrency, delayMs, bodyBytes, seed);

        boolean newFile = !new File(outFile).exists();
        try (PrintWriter csv = new PrintWriter(new FileWriter(outFile, true))) {
            if (newFile) {
                csv.println("sweep,capacity,zipf_s,objects,ttl_s,concurrency,origin_delay_ms,requests,"
                        + "hit_pct,revalidated_pct,miss_pct,mean_ms,p50_ms,p95_ms,p99_ms,hit_p50_ms,miss_p50_ms,"
                        + "requests_per_sec,origin_requests,ideal_static_hit_pct,latency_saving_pct,errors");
            }

            // Baseline: no proxy, no cache.
            Result base = run("baseline", 0, 1.0, 300, 0, 0);
            System.out.println("Baseline (clients talk to the origin directly, no cache):");
            printHeader();
            printRow(base);
            write(csv, base);
            double baseMean = base.meanMs;

            int[] caps = {objects / 50, objects / 20, objects / 10, objects / 5, objects / 2, objects};
            System.out.println("\nSweep 1: cache capacity  (Zipf s = 1.0, responses fresh for 300 s)");
            printHeader();
            for (int c : caps) {
                Result r = run("capacity", Math.max(1, c), 1.0, 300, baseMean, 0);
                printRow(r);
                write(csv, r);
            }

            double[] skews = {0.0, 0.6, 0.8, 1.0, 1.2};
            int mid = Math.max(1, objects / 10);
            System.out.println("\nSweep 2: popularity skew  (cache holds " + mid + " of " + objects + " objects)");
            printHeader();
            for (double s : skews) {
                Result r = run("skew", mid, s, 300, baseMean, 0);
                printRow(r);
                write(csv, r);
            }

            int[] ttls = {1, 3, 10, 300};
            System.out.println("\nSweep 3: freshness lifetime  (cache holds all " + objects + " objects, Zipf s = 1.0,"
                    + " clients pause " + thinkMs + " ms between requests so entries can expire)");
            printHeader();
            for (int ttl : ttls) {
                Result r = run("ttl", objects, 1.0, ttl, baseMean, thinkMs);
                printRow(r);
                write(csv, r);
            }
        }
        System.out.println("\nDone. Results appended to " + outFile);
    }

    // ------------------------------------------------------------------

    /** One measured run. capacity == 0 means clients talk to the origin directly. */
    private Result run(String sweep, int capacity, double s, int ttl, double baselineMeanMs, int think)
            throws Exception {
        boolean direct = capacity == 0;
        ZipfSampler zipf = new ZipfSampler(objects, s);
        int[] seq = sequences.computeIfAbsent(s, k -> zipf.sequence(warmup + requests, seed));

        // Cold cache and a fresh proxy for every run.
        ServerSocket proxySocket = null;
        int targetPort = originPort;
        if (!direct) {
            proxySocket = new ServerSocket(0);
            targetPort = proxySocket.getLocalPort();
            final ServerSocket ps = proxySocket;
            final LruCache<String, CacheEntry> cache = new LruCache<>(capacity);
            Thread pt = new Thread(() -> ProxyServer.serve(ps, cache));
            pt.setDaemon(true);
            pt.start();
        }

        byte[][] req = new byte[objects][];
        for (int id = 0; id < objects; id++) {
            String pq = "/obj/" + id + "?cc=" + URLEncoder.encode("max-age=" + ttl, StandardCharsets.UTF_8)
                    + "&etag=v" + id + "&delay=" + delayMs + "&size=" + bodyBytes;
            String line = direct
                    ? "GET " + pq + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    : "GET http://localhost:" + originPort + pq
                            + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            req[id] = line.getBytes(StandardCharsets.ISO_8859_1);
        }

        phase(targetPort, req, seq, 0, warmup, null, null, think);                 // warm the cache (not measured)
        int originBefore = origin.requestCount();
        long[] lat = new long[requests];
        byte[] cls = new byte[requests];
        long t0 = System.nanoTime();
        phase(targetPort, req, seq, warmup, warmup + requests, lat, cls, think);
        double seconds = (System.nanoTime() - t0) / 1e9;
        int originRequests = origin.requestCount() - originBefore;

        if (proxySocket != null) {
            proxySocket.close();
        }
        Thread.sleep(pauseSec * 1000L);

        Result r = new Result();
        r.sweep = sweep;
        r.capacity = capacity;
        r.zipfS = s;
        r.ttl = ttl;
        r.measured = requests;
        r.originRequests = originRequests;
        r.reqPerSec = requests / seconds;
        r.idealHitPct = direct ? 0 : 100.0 * zipf.topMass(capacity);

        long[] all = new long[requests];
        int nAll = 0;
        long[] hits = new long[requests];
        int nHit = 0;
        long[] misses = new long[requests];
        int nMiss = 0;
        double sum = 0;
        for (int i = 0; i < requests; i++) {
            switch (cls[i]) {
                case HIT: r.hit++; hits[nHit++] = lat[i]; break;
                case REVALIDATED: r.revalidated++; break;
                case MISS: r.miss++; misses[nMiss++] = lat[i]; break;
                default: r.errors++;
            }
            if (cls[i] != ERROR) {
                all[nAll++] = lat[i];
                sum += lat[i];
            }
        }
        r.meanMs = nAll == 0 ? 0 : sum / nAll / 1e6;
        r.p50 = percentileMs(all, nAll, 50);
        r.p95 = percentileMs(all, nAll, 95);
        r.p99 = percentileMs(all, nAll, 99);
        r.hitP50 = percentileMs(hits, nHit, 50);
        r.missP50 = percentileMs(misses, nMiss, 50);
        r.savingPct = baselineMeanMs > 0 ? 100.0 * (1 - r.meanMs / baselineMeanMs) : 0;
        return r;
    }

    /** Runs requests seq[from..to) with {@code concurrency} client threads; records into lat/cls if non-null. */
    private void phase(int port, byte[][] req, int[] seq, int from, int to, long[] lat, byte[] cls, int think)
            throws InterruptedException {
        AtomicInteger next = new AtomicInteger(from);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Thread[] threads = new Thread[concurrency];
        for (int t = 0; t < concurrency; t++) {
            threads[t] = new Thread(() -> {
                int i;
                while ((i = next.getAndIncrement()) < to) {
                    long start = System.nanoTime();
                    byte kind = ERROR;
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(loopback, port), 10_000);
                        s.setSoTimeout(15_000);
                        s.getOutputStream().write(req[seq[i]]);
                        s.getOutputStream().flush();
                        byte[] resp = s.getInputStream().readAllBytes();
                        String head = new String(resp, 0, Math.min(resp.length, 700), StandardCharsets.ISO_8859_1);
                        if (head.startsWith("HTTP/1.1 200") && resp.length >= bodyBytes) {
                            kind = head.contains("X-Cache: HIT") ? HIT
                                    : head.contains("X-Cache: REVALIDATED") ? REVALIDATED : MISS;
                        }
                    } catch (Exception e) {
                        // counted as an error below
                    }
                    if (lat != null) {
                        lat[i - from] = System.nanoTime() - start;
                        cls[i - from] = kind;
                    }
                    if (think > 0) {
                        try {
                            Thread.sleep(think);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            });
            threads[t].start();
        }
        for (Thread th : threads) {
            th.join();
        }
    }

    private static double percentileMs(long[] data, int n, double p) {
        if (n == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(data, n);
        Arrays.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * n) - 1;
        return copy[Math.max(0, Math.min(idx, n - 1))] / 1e6;
    }

    // ---- output ----

    private void printHeader() {
        System.out.println(String.format("%-9s %-6s %-6s %7s %7s %7s %8s %8s %8s %8s %9s %8s",
                "capacity", "zipf_s", "ttl_s", "hit%", "reval%", "miss%", "mean_ms", "p50_ms", "p95_ms", "p99_ms",
                "origin_req", "saving%"));
    }

    private void printRow(Result r) {
        System.out.println(String.format(Locale.ROOT,
                "%-9s %-6.1f %-6d %7.1f %7.1f %7.1f %8.2f %8.2f %8.2f %8.2f %9d %8.1f%s",
                r.capacity == 0 ? "none" : String.valueOf(r.capacity), r.zipfS, r.ttl,
                100.0 * r.hit / r.measured, 100.0 * r.revalidated / r.measured, 100.0 * r.miss / r.measured,
                r.meanMs, r.p50, r.p95, r.p99, r.originRequests, r.savingPct,
                r.errors > 0 ? "   (" + r.errors + " errors!)" : ""));
    }

    private void write(PrintWriter w, Result r) {
        w.println(String.format(Locale.ROOT,
                "%s,%d,%.1f,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f,%d,%.1f,%.1f,%d",
                r.sweep, r.capacity, r.zipfS, objects, r.ttl, concurrency, delayMs, r.measured,
                100.0 * r.hit / r.measured, 100.0 * r.revalidated / r.measured, 100.0 * r.miss / r.measured,
                r.meanMs, r.p50, r.p95, r.p99, r.hitP50, r.missP50, r.reqPerSec, r.originRequests,
                r.idealHitPct, r.savingPct, r.errors));
        w.flush();
    }
}

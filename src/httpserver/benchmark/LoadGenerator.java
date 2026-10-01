package httpserver.benchmark;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoadGenerator {
    private static final byte[] REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes();

    public static Stats run(String host, int port, int concurrency,
                            int warmupSec, int measureSec) throws Exception {
        Stats stats = new Stats(2_000_000);
        long start = System.nanoTime();
        long measureStart = start + warmupSec * 1_000_000_000L;
        long end = measureStart + measureSec * 1_000_000_000L;

        ExecutorService clients = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            clients.submit(() -> {
                byte[] buf = new byte[512];
                while (System.nanoTime() < end) {
                    long t0 = System.nanoTime();
                    boolean counted = t0 >= measureStart;   // skip warmup
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), 5000);
                        s.setSoTimeout(5000);
                        OutputStream out = s.getOutputStream();
                        out.write(REQUEST);
                        out.flush();

                        InputStream in = s.getInputStream();
                        int n = in.read(buf);
                        boolean is503 = n >= 12
                                && new String(buf, 0, 12).equals("HTTP/1.1 503");
                        while (in.read(buf) != -1) { }      // read until server closes

                        long elapsed = System.nanoTime() - t0;
                        if (counted) {
                            if (n <= 0) stats.errors.incrementAndGet();
                            else if (is503) stats.rejected503.incrementAndGet();
                            else stats.recordSuccess(elapsed);
                        }
                    } catch (Exception e) {
                      if (counted) stats.errors.incrementAndGet();
                      if (stats.errors.get() == 1) System.err.println("First error: " + e);
                    }
                }
            });
        }
        clients.shutdown();
        clients.awaitTermination(warmupSec + measureSec + 30, TimeUnit.SECONDS);
        return stats;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8081;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        int warmup = 2, measure = 10;

        System.out.println("Running: concurrency=" + concurrency
                + ", warmup=" + warmup + "s, measure=" + measure + "s");
        Stats s = run(host, port, concurrency, warmup, measure);

        System.out.printf("requests/sec : %.1f%n", s.requestsPerSec(measure));
        System.out.printf("p50 latency  : %.2f ms%n", s.percentileMs(50));
        System.out.printf("p95 latency  : %.2f ms%n", s.percentileMs(95));
        System.out.printf("p99 latency  : %.2f ms%n", s.percentileMs(99));
        System.out.println("503 rejected : " + s.rejected503.get());
        System.out.println("errors       : " + s.errors.get());
    }
}
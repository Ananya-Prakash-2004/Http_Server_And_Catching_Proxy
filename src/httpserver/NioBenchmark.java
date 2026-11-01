package httpserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Simple load generator for NioEventLoopServer.
 *
 * Usage: java httpserver.NioBenchmark [port] [requestsPerClient] [concurrency ...]
 * Example: java httpserver.NioBenchmark 8082 200 1 10 25 50
 *
 * The benchmark opens one persistent connection per client worker, performs a
 * short warm-up, then measures request latency and aggregate throughput.
 */
public final class NioBenchmark {
    private static final int DEFAULT_PORT = 8082;
    private static final int DEFAULT_REQUESTS_PER_CLIENT = 200;
    private static final int WARMUP_REQUESTS = 5;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private NioBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int requestsPerClient = args.length >= 2
                ? Integer.parseInt(args[1]) : DEFAULT_REQUESTS_PER_CLIENT;
        int[] concurrencyLevels;
        if (args.length >= 3) {
            concurrencyLevels = new int[args.length - 2];
            for (int i = 2; i < args.length; i++) {
                concurrencyLevels[i - 2] = Integer.parseInt(args[i]);
            }
        } else {
            concurrencyLevels = new int[] {1, 10, 25, 50};
        }

        System.out.println("Target: http://127.0.0.1:" + port + "/");
        System.out.println("Requests per client per level: " + requestsPerClient);
        System.out.println("concurrency,target_requests,completed,not_completed,seconds,requests_per_second,p50_ms,p95_ms,p99_ms");

        for (int concurrency : concurrencyLevels) {
            if (concurrency < 1) {
                throw new IllegalArgumentException("Concurrency must be >= 1");
            }
            runLevel(port, requestsPerClient, concurrency);
        }
    }

    private static void runLevel(int port, int requestsPerClient, int concurrency) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClientResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(new ClientTask(port, requestsPerClient, ready, start)));
        }

        try {
            ready.await(); // Connections and warm-up requests complete before timing starts.
            long startedAt = System.nanoTime();
            start.countDown();

            long completed = 0;
            List<Long> allLatencies = new ArrayList<>(concurrency * requestsPerClient);
            for (Future<ClientResult> future : futures) {
                ClientResult result = future.get();
                completed += result.latenciesNanos.size();
                allLatencies.addAll(result.latenciesNanos);
            }
            long targetRequests = (long) concurrency * requestsPerClient;
            long notCompleted = targetRequests - completed;
            long elapsedNanos = System.nanoTime() - startedAt;
            double seconds = elapsedNanos / 1_000_000_000.0;
            double requestsPerSecond = seconds == 0.0 ? 0.0 : completed / seconds;

            Collections.sort(allLatencies);
            System.out.printf(Locale.ROOT,
                    "%d,%d,%d,%d,%.3f,%.2f,%.3f,%.3f,%.3f%n",
                    concurrency, targetRequests, completed,
                    notCompleted, seconds, requestsPerSecond,
                    percentileMillis(allLatencies, 50),
                    percentileMillis(allLatencies, 95),
                    percentileMillis(allLatencies, 99));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static final class ClientTask implements Callable<ClientResult> {
        private final int port;
        private final int requests;
        private final CountDownLatch ready;
        private final CountDownLatch start;

        private ClientTask(int port, int requests, CountDownLatch ready, CountDownLatch start) {
            this.port = port;
            this.requests = requests;
            this.ready = ready;
            this.start = start;
        }

        @Override
        public ClientResult call() {
            ClientResult result = new ClientResult();
            boolean announcedReady = false;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
                socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();

                // Warm up connection reuse / file serving; do not record these latencies.
                for (int i = 0; i < WARMUP_REQUESTS; i++) {
                    sendGet(out);
                    readResponse(in);
                }

                ready.countDown();
                announcedReady = true;
                start.await();

                for (int i = 0; i < requests; i++) {
                    long requestStarted = System.nanoTime();
                    sendGet(out);
                    int status = readResponse(in);
                    long requestEnded = System.nanoTime();
                    if (status != 200) {
                        throw new IOException("Expected HTTP 200, received " + status);
                    }
                    result.latenciesNanos.add(requestEnded - requestStarted);
                }
            } catch (Exception e) {
                System.err.println("Benchmark client failed: " + e.getMessage());
            } finally {
                if (!announcedReady) {
                    ready.countDown();
                }
            }
            return result;
        }
    }

    private static final class ClientResult {
        private final List<Long> latenciesNanos = new ArrayList<>();
    }

    private static void sendGet(OutputStream out) throws IOException {
        String request = "GET / HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads the response headers, then exactly Content-Length body bytes. */
    private static int readResponse(BufferedInputStream in) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous1 = -1;
        int previous2 = -1;
        int previous3 = -1;
        boolean endFound = false;

        while (true) {
            int value = in.read();
            if (value == -1) {
                throw new IOException("Server closed connection before response headers completed");
            }
            headerBytes.write(value);
            if (previous1 == '\r' && previous2 == '\n'
                    && previous3 == '\r' && value == '\n') {
                endFound = true;
                break;
            }
            previous1 = previous2;
            previous2 = previous3;
            previous3 = value;
            if (headerBytes.size() > 64 * 1024) {
                throw new IOException("Response headers exceed 64 KiB");
            }
        }
        if (!endFound) {
            throw new IOException("Incomplete response header");
        }

        String headerText = new String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1);
        String[] lines = headerText.split("\r\n");
        String[] statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("Malformed status line: " + lines[0]);
        }
        int status = Integer.parseInt(statusParts[1]);
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(lines[i].substring(0, colon).trim())) {
                contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim());
                break;
            }
        }
        if (contentLength < 0) {
            throw new IOException("Negative Content-Length");
        }
        byte[] bodyChunk = new byte[8192];
        int remaining = contentLength;
        while (remaining > 0) {
            int count = in.read(bodyChunk, 0, Math.min(bodyChunk.length, remaining));
            if (count == -1) {
                throw new IOException("Server closed connection before response body completed");
            }
            remaining -= count;
        }
        return status;
    }

    private static double percentileMillis(List<Long> sortedLatencies, int percentile) {
        if (sortedLatencies.isEmpty()) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil((percentile / 100.0) * sortedLatencies.size());
        int index = Math.max(0, Math.min(sortedLatencies.size() - 1, rank - 1));
        return sortedLatencies.get(index) / 1_000_000.0;
    }
}

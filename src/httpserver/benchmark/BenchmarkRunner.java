package httpserver.benchmark;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;

public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        // args[0] = pool size the server was started with (just a label for the CSV)
        // args[1] = output file (optional)
        int poolSize = args.length > 0 ? Integer.parseInt(args[0]) : -1;
        String outFile = args.length > 1 ? args[1] : "results.csv";

        String host = "localhost";
        int port = 8081;
        int[] levels = {1, 2, 4, 8, 16, 32, 64, 128, 256};
        int warmup = 2, measure = 10;

        boolean newFile = !new File(outFile).exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(outFile, true))) {
            if (newFile) {
                w.println("pool_size,concurrency,requests_per_sec,p50_ms,p95_ms,p99_ms,rejected_503,errors");
            }
            for (int c : levels) {
                System.out.println("Running concurrency=" + c + " ...");
                Stats s = LoadGenerator.run(host, port, c, warmup, measure);

                String line = String.format(Locale.ROOT, "%d,%d,%.1f,%.2f,%.2f,%.2f,%d,%d",
                        poolSize, c, s.requestsPerSec(measure),
                        s.percentileMs(50), s.percentileMs(95), s.percentileMs(99),
                        s.rejected503.get(), s.errors.get());
                System.out.println(line);
                w.println(line);
                w.flush();

                Thread.sleep(3000);  
            }
        }
        System.out.println("Done. Results appended to " + outFile);
    }
}
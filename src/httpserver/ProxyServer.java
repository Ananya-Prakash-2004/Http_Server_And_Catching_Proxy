package httpserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for forward-proxy mode. Runs on its own port (default 8888) so it
 * never touches the teammates' Server / ConnectionHandler code.
 *
 * Usage: java httpserver.ProxyServer [port] [cacheCapacity]
 */
public class ProxyServer {
    private static final int DEFAULT_PORT = 8888;
    private static final int DEFAULT_CACHE_CAPACITY = 100;

    public static void main(String[] args) throws IOException {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int capacity = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_CACHE_CAPACITY;

        LruCache<String, CacheEntry> cache = new LruCache<>(capacity);

        // Ctrl+C prints the cache statistics.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Proxy stats: " + ProxyStats.summary());
            System.out.println("Cache stats: " + cache.stats());
        }));

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Forward proxy listening on port " + port
                    + " (cache capacity " + capacity + " entries)");
            serve(serverSocket, cache);
        }
    }

    /** Accept loop; returns when the server socket is closed. Also used by the tests. */
    public static void serve(ServerSocket serverSocket, LruCache<String, CacheEntry> cache) {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            while (true) {
                Socket client = serverSocket.accept();
                pool.execute(new ProxyHandler(client, cache));
            }
        } catch (IOException e) {
            // server socket closed: stop accepting
        } finally {
            pool.shutdown();
        }
    }
}

package httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    private static final int DEFAULT_PORT = 8081;
    private static final int DEFAULT_POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final int DEFAULT_QUEUE_SIZE = 100;

    public static void main(String[] args) throws IOException {
        int port      = Integer.getInteger("port", DEFAULT_PORT);
        int poolSize  = Integer.getInteger("pool.size", DEFAULT_POOL_SIZE);
        int queueSize = Integer.getInteger("queue.size", DEFAULT_QUEUE_SIZE);

        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                poolSize, poolSize,  
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize),  
                r -> new Thread(r, "worker-" + counter.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy()); 

        ServerSocket serverSocket = new ServerSocket(port, 128);
        System.out.println("Listening on port " + port
                + " (pool=" + poolSize + ", queue=" + queueSize + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { serverSocket.close(); } catch (IOException ignored) {}
            pool.shutdown();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }));

        while (!serverSocket.isClosed()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                break; 
            }
            try {
                pool.execute(new ConnectionHandler(client));
            } catch (RejectedExecutionException e) {
                rejectWith503(client); 
            }
        }
    }

    private static void rejectWith503(Socket s) {
        try (s; OutputStream out = s.getOutputStream()) {
            out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".getBytes());
            out.flush();
        } catch (IOException ignored) {}
    }
}
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
 * Usage: java httpserver.ProxyServer [port]
 */
public class ProxyServer {
    private static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) throws IOException {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Forward proxy listening on port " + port);
            while (true) {
                Socket client = serverSocket.accept();
                pool.execute(new ProxyHandler(client));
            }
        }
    }
}
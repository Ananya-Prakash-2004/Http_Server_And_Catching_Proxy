package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ConnectionHandler implements Runnable {
    private final Socket clientSocket;

    public ConnectionHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {

            ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];

            boolean headersComplete = false;

            while (!headersComplete) {
                int bytesRead = in.read(chunk);

                if (bytesRead == -1) {
                    System.out.println("Client closed connection before headers were complete");
                    return;
                }

                accumulator.write(chunk, 0, bytesRead);

                String soFar = accumulator.toString();
                if (soFar.contains("\r\n\r\n")) {
                    headersComplete = true;
                }
            }

            String fullRequestText = accumulator.toString();

            // Trim off everything from the blank line onward for now (no body handling yet)
            String headersOnly = fullRequestText.split("\r\n\r\n")[0];

            HttpRequest request;
            try {
                request = HttpRequestParser.parser(headersOnly);
            } catch (IllegalArgumentException e) {
                System.out.println("Failed to parse request: " + e.getMessage());
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                out.flush();
                return;
            }

            System.out.println("==== PARSED REQUEST ====");
            System.out.println(request);
            System.out.println("=========================");

            out.write("hello\n".getBytes());
            out.flush();

        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class ConnectionHandler implements Runnable {
    private final Socket clientSocket;
    private static StaticFileHandler fileHandler;

    static {
        try {
            fileHandler = new StaticFileHandler("www");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize document root", e);
        }
    }

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
                if (accumulator.toString().contains("\r\n\r\n")) {
                    headersComplete = true;
                }
            }

            String headersOnly = accumulator.toString().split("\r\n\r\n")[0];

            HttpRequest request;
            try {
                request = HttpRequestParser.parser(headersOnly);
            } catch (IllegalArgumentException e) {
                sendStatusOnly(out, "400 Bad Request");
                return;
            }

            System.out.println("Request: " + request.getMethod() + " " + request.getPath());

            String method = request.getMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendStatusOnly(out, "405 Method Not Allowed");
                return;
            }

            File file = fileHandler.resolveFile(request.getPath());
            if (file == null) {
                sendStatusOnly(out, "404 Not Found");
                return;
            }

            byte[] body = fileHandler.readFile(file);
            String mimeType = fileHandler.getMimeType(file);

            StringBuilder headerBuilder = new StringBuilder();
            headerBuilder.append("HTTP/1.1 200 OK\r\n");
            headerBuilder.append("Content-Type: ").append(mimeType).append("\r\n");
            headerBuilder.append("Content-Length: ").append(body.length).append("\r\n");
            headerBuilder.append("\r\n");

            out.write(headerBuilder.toString().getBytes());

            if (method.equals("GET")) {
                out.write(body); // HEAD gets headers only, no body
            }
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

    private void sendStatusOnly(OutputStream out, String statusLine) throws IOException {
        String response = "HTTP/1.1 " + statusLine + "\r\nContent-Length: 0\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }
}
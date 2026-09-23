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
                System.out.println("Read " + bytesRead + " bytes this call, "
                        + accumulator.size() + " bytes accumulated so far");

                String soFar = accumulator.toString();
                if (soFar.contains("\r\n\r\n")) {
                    headersComplete = true;
                }
            }

            String fullRequestText = accumulator.toString();
            System.out.println("==== FULL REQUEST HEADERS RECEIVED ====");
            System.out.println(fullRequestText);
            System.out.println("========================================");

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
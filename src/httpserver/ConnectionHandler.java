package httpserver;

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
            byte[] buffer = new byte[1024];
            int bytesRead = in.read(buffer);

            if (bytesRead == -1) {
                System.out.println("Client closed connection with no data");
                return;

            }

            String received = new String(buffer, 0, bytesRead);
            System.out.println("---- Received " + bytesRead + " bytes ----");
            System.out.println(received);
            System.out.println("-----------------------------------");
               out.write("hello\n".getBytes());
            out.flush();
        }catch(IOException e){
                 System.err.println("Error handling connection: " + e.getMessage());
        } finally {
            try{
                clientSocket.close();
            }catch(IOException ignored){
            }
        }
    }
}
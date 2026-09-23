package httpserver;

import java.io.OutputStream;
import java.net.Socket;

public class SplitRequestTestClient {
    public static void main(String[] args) throws Exception {
        Socket socket = new Socket("localhost", 8081);
        OutputStream out = socket.getOutputStream();

        // Send the FIRST half of a request
        String firstHalf = "GET /index.htm";
        out.write(firstHalf.getBytes());
        out.flush();
        System.out.println("Sent first half: " + firstHalf);

        // Simulate network delay before the rest arrives
        Thread.sleep(1000);

        // Send the SECOND half
        String secondHalf = "l HTTP/1.1\r\nHost: localhost\r\n\r\n";
        out.write(secondHalf.getBytes());
        out.flush();
        System.out.println("Sent second half: " + secondHalf);

        Thread.sleep(500); // give server time to respond
        socket.close();
    }
}
package httpserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Server {
    private static final int PORT =8081;

    public static void main(String[] args)throws IOException{
        ServerSocket serverSocket= new ServerSocket(PORT);
        System.out.println("Listening on port " + PORT);

        while(true){
            Socket clienSocket = serverSocket.accept();
            System.out.println("Accepted connection from " + clienSocket.getRemoteSocketAddress());

            Thread t = new Thread(new ConnectionHandler(clienSocket));
            t.start();
        }
    }
}

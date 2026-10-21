package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Step 1: plain forwarding (no caching yet).
 *
 * Flow: read request from client -> parse with the team's HttpRequestParser ->
 * open a socket to the origin -> send a rewritten request -> relay the
 * response bytes back to the client untouched.
 */
public class ProxyHandler implements Runnable {

    private static final int CLIENT_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int ORIGIN_READ_TIMEOUT_MS = 10_000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    // Hop-by-hop headers (RFC 7230 section 6.1) must not be forwarded.
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authorization", "proxy-authenticate");

    private final Socket clientSocket;

    public ProxyHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (Socket client = clientSocket;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            client.setSoTimeout(CLIENT_TIMEOUT_MS);

            // 1. Read the full header block (same buffering idea as ConnectionHandler).
            String headerText = readHeaderBlock(in);
            if (headerText == null) {
                return; // client closed or sent nothing
            }

            // 2. Parse using the teammate's parser.
            HttpRequest request;
            try {
                request = HttpRequestParser.parser(headerText);
            } catch (IllegalArgumentException e) {
                sendError(out, 400, "Bad Request");
                return;
            }

            String method = request.getMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendError(out, 405, "Method Not Allowed");
                return;
            }

            // 3. A proxy receives absolute-form targets: GET http://host:port/path HTTP/1.1
            URI target;
            try {
                target = new URI(request.getPath());
            } catch (URISyntaxException e) {
                sendError(out, 400, "Bad Request");
                return;
            }
            if (target.getScheme() == null || !target.getScheme().equalsIgnoreCase("http")
                    || target.getHost() == null) {
                sendError(out, 400, "Bad Request"); // not an absolute http:// URL
                return;
            }

            String host = target.getHost();
            int port = target.getPort() == -1 ? 80 : target.getPort();
            String originPath = target.getRawPath();
            if (originPath == null || originPath.isEmpty()) {
                originPath = "/";
            }
            if (target.getRawQuery() != null) {
                originPath += "?" + target.getRawQuery();
            }

            System.out.println("Proxy: " + method + " " + host + ":" + port + originPath);

            // 4. Connect to the origin and forward.
            forward(out, request, method, host, port, originPath);

        } catch (IOException e) {
            System.err.println("Proxy connection error: " + e.getMessage());
        }
    }

    private void forward(OutputStream clientOut, HttpRequest request, String method,
                         String host, int port, String originPath) throws IOException {
        try (Socket origin = new Socket()) {
            try {
                origin.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } catch (SocketTimeoutException e) {
                sendError(clientOut, 504, "Gateway Timeout");
                return;
            } catch (IOException e) { // includes UnknownHostException, ConnectException
                sendError(clientOut, 502, "Bad Gateway");
                return;
            }
            origin.setSoTimeout(ORIGIN_READ_TIMEOUT_MS);

            // Send the request upstream in origin-form with a clean header set.
            OutputStream originOut = origin.getOutputStream();
            originOut.write(buildOriginRequest(request, method, host, port, originPath)
                    .getBytes(StandardCharsets.ISO_8859_1));
            originOut.flush();

            // Relay the response untouched. We asked for "Connection: close", so the
            // origin closing the socket marks the end of the response.
            InputStream originIn = origin.getInputStream();
            byte[] buf = new byte[8192];
            boolean gotAnyBytes = false;
            try {
                int n;
                while ((n = originIn.read(buf)) != -1) {
                    clientOut.write(buf, 0, n);
                    gotAnyBytes = true;
                }
            } catch (SocketTimeoutException e) {
                if (!gotAnyBytes) {
                    sendError(clientOut, 504, "Gateway Timeout");
                    return;
                }
                // otherwise: partial response already sent; just close.
            }
            if (!gotAnyBytes) {
                sendError(clientOut, 502, "Bad Gateway"); // origin closed with no response
                return;
            }
            clientOut.flush();
        }
    }

    private String buildOriginRequest(HttpRequest request, String method,
                                      String host, int port, String originPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(originPath).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(port == 80 ? host : host + ":" + port).append("\r\n");

        for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
            String name = h.getKey(); // already lowercased by HttpRequest.addHeader
            if (name.equals("host") || HOP_BY_HOP.contains(name)) {
                continue;
            }
            sb.append(name).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }

    /** Reads until \r\n\r\n; returns the header text without the blank line, or null. */
    private String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        while (true) {
            int n;
            try {
                n = in.read(chunk);
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (n == -1) {
                return null;
            }
            acc.write(chunk, 0, n);
            String text = acc.toString(StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            if (end != -1) {
                return text.substring(0, end);
            }
            if (acc.size() > MAX_HEADER_BYTES) {
                return null;
            }
        }
    }

    private void sendError(OutputStream out, int code, String reason) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
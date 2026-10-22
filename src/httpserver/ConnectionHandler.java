package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class ConnectionHandler implements Runnable {
    private static final int IDLE_TIMEOUT_MS = 5000;
    private static final int MAX_REQUESTS_PER_CONNECTION = 100;

    private static StaticFileHandler fileHandler;
    static {
        try {
            fileHandler = new StaticFileHandler("www");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize document root", e);
        }
    }

    private final Socket clientSocket;

    public ConnectionHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (InputStream in = clientSocket.getInputStream();
             OutputStream out = clientSocket.getOutputStream()) {

            clientSocket.setSoTimeout(IDLE_TIMEOUT_MS);
            RequestReader reader = new RequestReader(in);
            int served = 0;

            while (served < MAX_REQUESTS_PER_CONNECTION) {
                boolean includeBody = true;
                boolean keepAlive = false;
                HttpResponse response;

                try {
                    String headerBlock = reader.readHeaderBlock();
                    if (headerBlock == null) {
                        break;
                    }
                    HttpRequest request = HttpRequestParser.parse(headerBlock);
                    System.out.println("Request: " + request.getMethod() + " " + request.getPath());

                    includeBody = !request.getMethod().equals("HEAD");
                    response = buildResponse(request);

                    boolean lastAllowed = (served + 1 >= MAX_REQUESTS_PER_CONNECTION);
                    keepAlive = wantsKeepAlive(request)
                            && isGetOrHead(request)
                            && !hasRequestBody(request)
                            && !lastAllowed;

                } catch (HttpParseException e) {
                    response = HttpResponse.error(e.getStatusCode(), e.getReason());
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    System.err.println("Unexpected error: " + e);
                    response = HttpResponse.error(500, "Internal Server Error");
                }

                response.write(out, includeBody, keepAlive);
                served++;

                if (!keepAlive) {
                    break;
                }
            }

        } catch (SocketTimeoutException e) {
            System.out.println("Idle timeout, closing connection " + clientSocket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean wantsKeepAlive(HttpRequest request) {
        String header = request.getHeader("Connection");
        String tokens = (header == null) ? "" : header.toLowerCase();
        if (request.getVersion().equals("HTTP/1.1")) {
            return !tokens.contains("close");
        }
        return tokens.contains("keep-alive");
    }

    private boolean isGetOrHead(HttpRequest request) {
        return request.getMethod().equals("GET") || request.getMethod().equals("HEAD");
    }

    private boolean hasRequestBody(HttpRequest request) {
        String length = request.getHeader("Content-Length");
        boolean hasLength = length != null && !length.trim().equals("0");
        return hasLength || request.getHeader("Transfer-Encoding") != null;
    }

    private HttpResponse buildResponse(HttpRequest request) throws IOException {
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return HttpResponse.error(405, "Method Not Allowed").header("Allow", "GET, HEAD");
        }

        if (request.getPath().equals("/stream")) {
            return streamDemo(request);
        }

        File file = fileHandler.resolveFile(request.getPath());
        if (file == null) {
            return HttpResponse.error(404, "Not Found");
        }

        long lastModified = file.lastModified() / 1000 * 1000;
        String lastModifiedHeader = HttpResponse.formatHttpDate(lastModified);

        String ifModifiedSince = request.getHeader("If-Modified-Since");
        if (ifModifiedSince != null) {
            long clientDate = HttpResponse.parseHttpDate(ifModifiedSince);
            if (clientDate != -1 && lastModified <= clientDate) {
                return new HttpResponse(304, "Not Modified")
                        .header("Last-Modified", lastModifiedHeader);
            }
        }

        return new HttpResponse(200, "OK")
                .header("Content-Type", fileHandler.getMimeType(file))
                .header("Last-Modified", lastModifiedHeader)
                .body(fileHandler.readFile(file));
    }

    /** Demo of an unknown-length body: 5 pieces, one every half second. */
    private HttpResponse streamDemo(HttpRequest request) throws IOException {
        int[] sent = {0};
        HttpResponse.ChunkSource source = () -> {
            if (sent[0] >= 5) {
                return null;
            }
            sent[0]++;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return ("Chunk " + sent[0] + " of 5\n").getBytes(StandardCharsets.UTF_8);
        };

        // HTTP/1.0 clients can't decode chunked, so gather the pieces and send a normal body
        if (request.getVersion().equals("HTTP/1.0")) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] part;
            while ((part = source.next()) != null) {
                all.write(part);
            }
            return new HttpResponse(200, "OK")
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(all.toByteArray());
        }

        return new HttpResponse(200, "OK")
                .header("Content-Type", "text/plain; charset=utf-8")
                .chunked(source);
    }
}
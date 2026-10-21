package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ConnectionHandler implements Runnable {
    private static final int MAX_HEADER_BYTES = 8192;

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

            boolean includeBody = true;
            HttpResponse response;

            try {
                String headerBlock = readHeaderBlock(in);
                if (headerBlock == null) {
                    return; // client hung up before sending a full request
                }
                HttpRequest request = HttpRequestParser.parse(headerBlock);
                includeBody = !request.getMethod().equals("HEAD");
                System.out.println("Request: " + request.getMethod() + " " + request.getPath());
                response = buildResponse(request);

            } catch (HttpParseException e) {
                response = HttpResponse.error(e.getStatusCode(), e.getReason());
            } catch (IOException e) {
                throw e; // network problem: let the outer handler deal with it
            } catch (Exception e) {
                System.err.println("Unexpected error: " + e);
                response = HttpResponse.error(500, "Internal Server Error");
            }

            response.write(out, includeBody);

        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Buffering layer: accumulate until \r\n\r\n, with a hard size cap. Returns null if client closed early. */
    private String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream accumulator = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];

        while (true) {
            int bytesRead = in.read(chunk);
            if (bytesRead == -1) {
                return null;
            }
            accumulator.write(chunk, 0, bytesRead);

            String soFar = accumulator.toString(StandardCharsets.ISO_8859_1);
            int end = soFar.indexOf("\r\n\r\n");
            if (end != -1) {
                if (end > MAX_HEADER_BYTES) {
                    throw new HttpParseException(431, "Request Header Fields Too Large");
                }
                return soFar.substring(0, end);
            }
            if (accumulator.size() > MAX_HEADER_BYTES) {
                throw new HttpParseException(431, "Request Header Fields Too Large");
            }
        }
    }

    private HttpResponse buildResponse(HttpRequest request) throws IOException {
        String method = request.getMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return HttpResponse.error(405, "Method Not Allowed").header("Allow", "GET, HEAD");
        }

        File file = fileHandler.resolveFile(request.getPath());
        if (file == null) {
            return HttpResponse.error(404, "Not Found");
        }

        // HTTP dates only have 1-second resolution, so truncate before comparing
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
}
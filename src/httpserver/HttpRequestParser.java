package httpserver;

public class HttpRequestParser {

    private static final int MAX_URI_LENGTH = 2000;

    public static HttpRequest parse(String rawRequest) {
        HttpRequest request = new HttpRequest();

        String[] lines = rawRequest.split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) {
            throw new HttpParseException(400, "Bad Request");
        }

        String[] parts = lines[0].split(" ");
        if (parts.length != 3) {
            throw new HttpParseException(400, "Bad Request");
        }

        String method = parts[0];
        String path = parts[1];
        String version = parts[2];

        if (path.length() > MAX_URI_LENGTH) {
            throw new HttpParseException(414, "URI Too Long");
        }

        if (!version.startsWith("HTTP/")) {
            throw new HttpParseException(400, "Bad Request");
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpParseException(505, "HTTP Version Not Supported");
        }

        request.setMethod(method);
        request.setPath(path);
        request.setVersion(version);

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break;
            }
            String[] headerParts = line.split(":", 2);
            if (headerParts.length != 2) {
                throw new HttpParseException(400, "Bad Request"); // header with no colon
            }
            request.addHeader(headerParts[0].trim(), headerParts[1].trim());
        }

        return request;
    }
}
package httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class HttpResponse {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final int statusCode;
    private final String reason;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    public HttpResponse(int statusCode, String reason) {
        this.statusCode = statusCode;
        this.reason = reason;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.body = body;
        return this;
    }

    public static HttpResponse error(int code, String reason) {
        return new HttpResponse(code, reason)
                .header("Content-Type", "text/plain; charset=utf-8")
                .body((code + " " + reason + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static String formatHttpDate(long epochMillis) {
        return HTTP_DATE.format(Instant.ofEpochMilli(epochMillis));
    }

    public static long parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), HTTP_DATE).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }

    public void write(OutputStream out, boolean includeBody) throws IOException {
        write(out, includeBody, false);
    }

    public void write(OutputStream out, boolean includeBody, boolean keepAlive) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        sb.append("Date: ").append(formatHttpDate(System.currentTimeMillis())).append("\r\n");

        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }

        boolean bodyAllowed = statusCode != 304;
        if (bodyAllowed) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (includeBody && bodyAllowed && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
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

    /** Produces a body piece by piece when the total size isn't known up front. Returns null when done. */
    public interface ChunkSource {
        byte[] next() throws IOException;
    }

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final int statusCode;
    private final String reason;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private ChunkSource chunkSource;

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

    /** Send the body with Transfer-Encoding: chunked, pulling pieces from the source. */
    public HttpResponse chunked(ChunkSource source) {
        this.chunkSource = source;
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
        boolean isChunked = (chunkSource != null);
        boolean bodyAllowed = statusCode != 304;

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        sb.append("Date: ").append(formatHttpDate(System.currentTimeMillis())).append("\r\n");

        for (Map.Entry<String, String> h : headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }

        if (isChunked) {
            sb.append("Transfer-Encoding: chunked\r\n");
        } else if (bodyAllowed) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

        if (includeBody) {
            if (isChunked) {
                out.flush(); // get the headers out before the first (possibly slow) chunk
                writeChunks(out);
            } else if (bodyAllowed && body.length > 0) {
                out.write(body);
            }
        }
        out.flush();
    }

    private void writeChunks(OutputStream out) throws IOException {
        byte[] chunk;
        while ((chunk = chunkSource.next()) != null) {
            if (chunk.length == 0) {
                continue; // a zero-size chunk means "end of body", so never send an empty one
            }
            byte[] sizeLine = (Integer.toHexString(chunk.length) + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);

            // build  <hex size>\r\n<data>\r\n  as ONE array so it goes out in one write
            byte[] framed = new byte[sizeLine.length + chunk.length + 2];
            System.arraycopy(sizeLine, 0, framed, 0, sizeLine.length);
            System.arraycopy(chunk, 0, framed, sizeLine.length, chunk.length);
            framed[framed.length - 2] = '\r';
            framed[framed.length - 1] = '\n';

            out.write(framed);
            out.flush();
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); // last chunk
    }
}
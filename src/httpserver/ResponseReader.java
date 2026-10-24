package httpserver;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads one HTTP/1.1 response from an origin socket and turns it into a
 * ProxyResponse. Handles the three ways a body can be framed:
 *   1. Transfer-Encoding: chunked   (decoded here)
 *   2. Content-Length: N            (read exactly N bytes)
 *   3. neither                      (body runs until the origin closes the socket)
 * Responses to HEAD, and 1xx / 204 / 304 responses, never have a body.
 */
public final class ResponseReader {

    private static final int MAX_LINE_BYTES = 16 * 1024;
    private static final int MAX_HEAD_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    // Removed while parsing: hop-by-hop headers (RFC 7230 6.1) plus the origin's framing,
    // which ProxyResponse.toBytes() regenerates.
    private static final Set<String> STRIPPED = Set.of(
            "connection", "proxy-connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization",
            "content-length");

    private ResponseReader() {
    }

    public static ProxyResponse read(InputStream rawIn, boolean headRequest) throws IOException {
        InputStream in = new BufferedInputStream(rawIn, 8192);

        int status;
        String reason;
        String httpVersion;
        List<String[]> allHeaders;

        // Skip interim 1xx responses (e.g. 100 Continue); a real response follows them.
        while (true) {
            int[] budget = {MAX_HEAD_BYTES};
            String statusLine = readLine(in, budget);
            if (statusLine == null) {
                throw new IOException("Origin closed the connection before sending a response");
            }
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                throw new IOException("Malformed status line from origin: " + statusLine);
            }
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed status code from origin: " + statusLine);
            }
            reason = parts.length == 3 ? parts[2] : "";
            httpVersion = parts[0];

            allHeaders = new ArrayList<>();
            while (true) {
                String line = readLine(in, budget);
                if (line == null) {
                    throw new IOException("Origin closed the connection in the middle of the headers");
                }
                if (line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                    continue; // malformed or obsolete folded line: ignore
                }
                allHeaders.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            }

            if (status >= 100 && status < 200 && status != 101) {
                continue;
            }
            break;
        }

        // Header names listed in "Connection: x, y" are hop-by-hop too.
        Set<String> dropped = new HashSet<>(STRIPPED);
        boolean closeToken = false;
        boolean keepAliveToken = false;
        for (String[] h : allHeaders) {
            if (h[0].equalsIgnoreCase("connection")) {
                for (String token : h[1].split(",")) {
                    String t = token.trim().toLowerCase(Locale.ROOT);
                    dropped.add(t);
                    closeToken |= t.equals("close");
                    keepAliveToken |= t.equals("keep-alive");
                }
            }
        }
        // HTTP/1.1 connections stay open unless the origin says "close"; HTTP/1.0 ones close unless
        // the origin says "keep-alive".
        boolean reusable = !closeToken && status != 101
                && (httpVersion.equals("HTTP/1.1") || keepAliveToken);
        List<String[]> kept = new ArrayList<>();
        String transferEncoding = null;
        String contentLengthText = null;
        for (String[] h : allHeaders) {
            String lower = h[0].toLowerCase(Locale.ROOT);
            if (lower.equals("transfer-encoding")) {
                transferEncoding = h[1].toLowerCase(Locale.ROOT);
            } else if (lower.equals("content-length") && contentLengthText == null) {
                contentLengthText = h[1];
            }
            if (!dropped.contains(lower)) {
                kept.add(h);
            }
        }

        long contentLength = -1;
        if (contentLengthText != null) {
            try {
                contentLength = Long.parseLong(contentLengthText.trim());
                if (contentLength < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length from origin: " + contentLengthText);
            }
        }

        boolean noBody = headRequest || status == 204 || status == 304 || (status >= 100 && status < 200);
        byte[] body;
        long declared;

        if (noBody) {
            body = new byte[0];
            declared = (status == 204 || status < 200) ? -1 : contentLength; // keep CL for HEAD / 304
        } else if (transferEncoding != null && transferEncoding.contains("chunked")) {
            body = readChunked(in);
            declared = body.length;
        } else if (contentLength >= 0) {
            if (contentLength > MAX_BODY_BYTES) {
                throw new IOException("Response body too large: " + contentLength);
            }
            body = in.readNBytes((int) contentLength);
            if (body.length < contentLength) {
                throw new IOException("Origin closed the connection before the full body arrived");
            }
            declared = body.length;
        } else {
            body = readUntilEof(in);
            declared = body.length;
            reusable = false; // the end of the body was the origin closing the connection
        }

        return new ProxyResponse(status, reason, kept, body, declared, reusable);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, new int[]{MAX_LINE_BYTES});
            if (sizeLine == null) {
                throw new IOException("Origin closed the connection in the middle of a chunked body");
            }
            int semi = sizeLine.indexOf(';'); // ignore chunk extensions
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
                if (size < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IOException("Bad chunk size from origin: " + sizeLine);
            }
            if (size == 0) {
                break;
            }
            if (out.size() + size > MAX_BODY_BYTES) {
                throw new IOException("Response body too large");
            }
            byte[] chunk = in.readNBytes((int) size);
            if (chunk.length < size) {
                throw new IOException("Origin closed the connection in the middle of a chunk");
            }
            out.write(chunk);
            String crlf = readLine(in, new int[]{MAX_LINE_BYTES});
            if (crlf == null || !crlf.isEmpty()) {
                throw new IOException("Missing CRLF after chunk data");
            }
        }
        // Trailers (rare): read and discard up to the final blank line.
        while (true) {
            String trailer = readLine(in, new int[]{MAX_HEAD_BYTES});
            if (trailer == null || trailer.isEmpty()) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static byte[] readUntilEof(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                throw new IOException("Response body too large");
            }
        }
        return out.toByteArray();
    }

    /** Reads up to CRLF (or LF). Returns null on EOF before any byte. budget[0] is decremented. */
    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (--budget[0] < 0) {
                throw new IOException("Header section from origin is too large");
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        if (!any) {
            return null;
        }
        throw new IOException("Origin closed the connection in the middle of a line");
    }
}

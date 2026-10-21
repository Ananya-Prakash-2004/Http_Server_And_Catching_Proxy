package httpserver;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * An origin response that has been fully read and parsed: status line, headers
 * and decoded body. Immutable, so one instance can safely be shared between the
 * cache and any number of client threads.
 *
 * Hop-by-hop headers and the origin's framing headers (Content-Length,
 * Transfer-Encoding) are removed when parsing; toBytes() writes fresh framing.
 */
public final class ProxyResponse {
    private final int statusCode;
    private final String reason;
    private final List<String[]> headers; // {name, value} pairs, original case/order, duplicates kept
    private final byte[] body;
    private final long declaredLength;    // Content-Length to emit, or -1 for none

    public ProxyResponse(int statusCode, String reason, List<String[]> headers,
                         byte[] body, long declaredLength) {
        this.statusCode = statusCode;
        this.reason = reason;
        this.headers = List.copyOf(headers);
        this.body = body;
        this.declaredLength = declaredLength;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReason() {
        return reason;
    }

    public List<String[]> getHeaders() {
        return headers;
    }

    public int bodySize() {
        return body.length;
    }

    /** First header with this name (case-insensitive), or null. */
    public String getHeader(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    /**
     * Serialises the response for the client.
     *
     * @param headOnly true to omit the body (HEAD requests)
     * @param xCache   value for an X-Cache header ("HIT"/"MISS"), or null for none
     */
    public byte[] toBytes(boolean headOnly, String xCache) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        for (String[] h : headers) {
            sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        if (declaredLength >= 0) {
            sb.append("Content-Length: ").append(declaredLength).append("\r\n");
        }
        if (xCache != null) {
            sb.append("X-Cache: ").append(xCache).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (headOnly || body.length == 0) {
            return head;
        }
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }
}
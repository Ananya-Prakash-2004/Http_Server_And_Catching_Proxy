package httpserver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /** Headers a 304 should repeat from the 200 it stands in for (RFC 7232 section 4.1). */
    private static final Set<String> NOT_MODIFIED_HEADERS = Set.of(
            "cache-control", "content-location", "date", "etag", "expires", "last-modified", "vary");

    /**
     * Returns a copy of this stored response whose headers are updated from a 304 Not Modified
     * (RFC 7234 section 4.3.4): every header the 304 carries replaces the stored header of the
     * same name. The stored Age is always dropped, because the response was just revalidated.
     * Status and body stay those of the stored response.
     */
    public ProxyResponse withHeadersFrom(ProxyResponse notModified) {
        Set<String> replaced = new HashSet<>();
        replaced.add("age");
        for (String[] h : notModified.headers) {
            replaced.add(h[0].toLowerCase(Locale.ROOT));
        }
        List<String[]> merged = new ArrayList<>();
        for (String[] h : headers) {
            if (!replaced.contains(h[0].toLowerCase(Locale.ROOT))) {
                merged.add(h);
            }
        }
        merged.addAll(notModified.headers);
        return new ProxyResponse(statusCode, reason, merged, body, declaredLength);
    }

    /** A "304 Not Modified" standing in for the given full response: right headers, no body. */
    public static ProxyResponse notModifiedFrom(ProxyResponse full) {
        List<String[]> kept = new ArrayList<>();
        for (String[] h : full.headers) {
            if (NOT_MODIFIED_HEADERS.contains(h[0].toLowerCase(Locale.ROOT))) {
                kept.add(h);
            }
        }
        return new ProxyResponse(304, "Not Modified", kept, new byte[0], -1);
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

    /** All values of a header joined with ", " (right for Cache-Control sent as several lines), or null. */
    public String getHeaderJoined(String name) {
        StringBuilder sb = null;
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                if (sb == null) {
                    sb = new StringBuilder(h[1]);
                } else {
                    sb.append(", ").append(h[1]);
                }
            }
        }
        return sb == null ? null : sb.toString();
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
        return toBytes(headOnly, xCache, -1);
    }

    /** As above; ageSeconds >= 0 adds an Age header (replacing any stored one), -1 adds none. */
    public byte[] toBytes(boolean headOnly, String xCache, long ageSeconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        for (String[] h : headers) {
            if (ageSeconds >= 0 && h[0].equalsIgnoreCase("age")) {
                continue;
            }
            sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        if (ageSeconds >= 0) {
            sb.append("Age: ").append(ageSeconds).append("\r\n");
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

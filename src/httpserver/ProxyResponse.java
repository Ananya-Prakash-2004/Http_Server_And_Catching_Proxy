package httpserver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


public final class ProxyResponse {
    private final int statusCode;
    private final String reason;
    private final List<String[]> headers; 
    private final byte[] body;
    private final long declaredLength; 
    private final boolean originConnectionReusable; 

    public ProxyResponse(int statusCode, String reason, List<String[]> headers,
                         byte[] body, long declaredLength) {
        this(statusCode, reason, headers, body, declaredLength, false);
    }

    public ProxyResponse(int statusCode, String reason, List<String[]> headers,
                         byte[] body, long declaredLength, boolean originConnectionReusable) {
        this.originConnectionReusable = originConnectionReusable;
        this.statusCode = statusCode;
        this.reason = reason;
        this.headers = List.copyOf(headers);
        this.body = body;
        this.declaredLength = declaredLength;
    }

    
    private static final Set<String> NOT_MODIFIED_HEADERS = Set.of(
            "cache-control", "content-location", "date", "etag", "expires", "last-modified", "vary");

    
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

    public static ProxyResponse notModifiedFrom(ProxyResponse full) {
        List<String[]> kept = new ArrayList<>();
        for (String[] h : full.headers) {
            if (NOT_MODIFIED_HEADERS.contains(h[0].toLowerCase(Locale.ROOT))) {
                kept.add(h);
            }
        }
        return new ProxyResponse(304, "Not Modified", kept, new byte[0], -1);
    }

    public boolean isOriginConnectionReusable() {
        return originConnectionReusable;
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

    
    public String getHeader(String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    
    public byte[] toBytes(boolean headOnly, String xCache) {
        return toBytes(headOnly, xCache, -1);
    }

    public byte[] toBytes(boolean headOnly, String xCache, long ageSeconds) {
        return toBytes(headOnly, xCache, ageSeconds, false);
    }

    public byte[] toBytes(boolean headOnly, String xCache, long ageSeconds, boolean keepAlive) {
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
        sb.append(keepAlive ? "Connection: keep-alive\r\n\r\n" : "Connection: close\r\n\r\n");

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

package httpserver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed Cache-Control header (works for request and response headers).
 * Directive names are case-insensitive; "max-age=60", "MAX-AGE=60" and
 * "max-age=\"60\"" all mean the same thing.
 */
public final class CacheControl {
    /** Upper bound for delta-seconds values (RFC 7234 section 1.2.1: 2^31). */
    private static final long MAX_SECONDS = 2147483648L;

    private final Map<String, String> directives;

    private CacheControl(Map<String, String> directives) {
        this.directives = directives;
    }

    /** headerValue may be null (header absent) and may contain several joined headers. */
    public static CacheControl parse(String headerValue) {
        Map<String, String> map = new HashMap<>();
        if (headerValue != null) {
            StringBuilder token = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i <= headerValue.length(); i++) {
                char c = i < headerValue.length() ? headerValue.charAt(i) : ',';
                if (c == '"') {
                    inQuotes = !inQuotes;
                }
                if (c == ',' && !inQuotes) {
                    addDirective(map, token.toString());
                    token.setLength(0);
                } else {
                    token.append(c);
                }
            }
        }
        return new CacheControl(map);
    }

    private static void addDirective(Map<String, String> map, String raw) {
        String token = raw.trim();
        if (token.isEmpty()) {
            return;
        }
        int eq = token.indexOf('=');
        String name = (eq < 0 ? token : token.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
        String value = eq < 0 ? "" : token.substring(eq + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        map.putIfAbsent(name, value); // if a directive repeats, the first one wins
    }

    public boolean has(String name) {
        return directives.containsKey(name);
    }

    /**
     * Value of a delta-seconds directive such as max-age.
     * Returns -1 if the directive is absent, 0 if its value is invalid (treated as
     * "already stale", the safe choice), and caps absurdly large values.
     */
    public long seconds(String name) {
        String v = directives.get(name);
        if (v == null) {
            return -1;
        }
        if (v.isEmpty()) {
            return 0;
        }
        try {
            long n = Long.parseLong(v);
            return n < 0 ? 0 : Math.min(n, MAX_SECONDS);
        } catch (NumberFormatException e) {
            return v.matches("\\d+") ? MAX_SECONDS : 0; // digits but overflowed -> huge
        }
    }
}

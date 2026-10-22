package httpserver;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** HTTP-date helpers (e.g. "Wed, 21 Oct 2026 07:28:00 GMT") used by Date / Expires / Last-Modified. */
final class HttpDates {
    private static final DateTimeFormatter OUT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private HttpDates() {
    }

    static String format(long epochMillis) {
        return OUT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Returns epoch millis, or null if the text is not a valid (IMF-fixdate) HTTP-date. */
    static Long parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

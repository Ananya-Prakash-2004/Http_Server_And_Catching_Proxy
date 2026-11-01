package httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class RequestReader {
    private static final int MAX_HEADER_BYTES = 8192;

    private final InputStream in;
    private byte[] buf = new byte[4096];
    private int len = 0;

    public RequestReader(InputStream in) {
        this.in = in;
    }

    public String readHeaderBlock() throws IOException {
        while (true) {
            int end = indexOfBlankLine();
            if (end != -1) {
                if (end > MAX_HEADER_BYTES) {
                    throw new HttpParseException(431, "Request Header Fields Too Large");
                }
                String block = new String(buf, 0, end, StandardCharsets.ISO_8859_1);
                consume(end + 4);
                return block;
            }

            if (len > MAX_HEADER_BYTES) {
                throw new HttpParseException(431, "Request Header Fields Too Large");
            }

            if (buf.length - len < 1024) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            int n = in.read(buf, len, buf.length - len);
            if (n == -1) {
                return null;
            }
            len += n;
        }
    }

    private int indexOfBlankLine() {
        for (int i = 0; i + 3 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void consume(int n) {
        System.arraycopy(buf, n, buf, 0, len - n);
        len -= n;
    }
}
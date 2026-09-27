package httpserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class StaticFileHandler {

    private static final Map<String, String> MIME_TYPES = new HashMap<>();
    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("ico", "image/x-icon");
    }

    private final File documentRoot;

    public StaticFileHandler(String documentRootPath) throws IOException {
        this.documentRoot = new File(documentRootPath).getCanonicalFile();
    }

    /**
     * Resolves a request path safely against the document root.
     * Returns null if the path escapes the document root (traversal attempt)
     * or if the resolved file does not exist.
     */
    public File resolveFile(String requestPath) {
        try {
            // Strip query string if present, and leading slash
            String cleanPath = requestPath.split("\\?")[0];
            if (cleanPath.equals("/")) {
                cleanPath = "/index.html"; // default file
            }

            File candidate = new File(documentRoot, cleanPath).getCanonicalFile();

            // Directory traversal check: candidate must live inside documentRoot
            if (!candidate.getPath().startsWith(documentRoot.getPath())) {
                System.out.println("Blocked directory traversal attempt: " + requestPath);
                return null;
            }

            if (!candidate.exists() || !candidate.isFile()) {
                return null;
            }

            return candidate;

        } catch (IOException e) {
            return null;
        }
    }

    public String getMimeType(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot == -1) {
            return "application/octet-stream";
        }
        String extension = name.substring(lastDot + 1).toLowerCase();
        return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    public byte[] readFile(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }
}
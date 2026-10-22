package httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;

/**
 * A selector-based HTTP/1.1 static-file server.
 *
 * This is the NIO/event-loop alternative to Server.java. It uses non-blocking
 * sockets, keeps per-connection input/output state, and reuses the team's
 * existing HttpRequestParser and StaticFileHandler.
 *
 * Note: static files are loaded synchronously through StaticFileHandler. Socket
 * operations are non-blocking, but disk reads can briefly occupy the event-loop
 * thread for large files. Moving file I/O off the loop can be a later extension.
 */
public final class NioEventLoopServer {
    private static final int DEFAULT_PORT = 8082; // Existing blocking server uses 8081.
    private static final int READ_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_REQUEST_TARGET_CHARS = 8 * 1024;
    private static final Charset HEADER_CHARSET = StandardCharsets.ISO_8859_1;
    private static final Charset RESPONSE_CHARSET = StandardCharsets.UTF_8;
    private static final byte[] HEADER_END = new byte[] {'\r', '\n', '\r', '\n'};

    private final int port;
    private final String documentRoot;
    private final StaticFileHandler fileHandler;

    private NioEventLoopServer(int port, String documentRoot) throws IOException {
        this.port = port;
        this.documentRoot = documentRoot;
        this.fileHandler = new StaticFileHandler(documentRoot);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String documentRoot = args.length >= 2 ? args[1] : "www";
        new NioEventLoopServer(port, documentRoot).run();
    }

    private void run() throws IOException {
        try (Selector selector = Selector.open();
             ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.configureBlocking(false);
            listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            listener.bind(new InetSocketAddress(port));
            listener.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("NIO event-loop server listening on port " + port);
            System.out.println("Document root: " + new File(documentRoot).getAbsolutePath());

            while (true) {
                // select() may wait; the channels themselves remain non-blocking.
                selector.select();
                Iterator<SelectionKey> selected = selector.selectedKeys().iterator();

                while (selected.hasNext()) {
                    SelectionKey key = selected.next();
                    selected.remove(); // Required: don't process this selected key again.
                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            acceptReadyClients(listener, selector);
                        } else {
                            if (key.isReadable()) {
                                readFromClient(key);
                            }
                            // A read may close/cancel the key, so check validity again.
                            if (key.isValid() && key.isWritable()) {
                                writeToClient(key);
                            }
                        }
                    } catch (CancelledKeyException ignored) {
                        // The connection may have been closed while processing it.
                    } catch (IOException e) {
                        if (key.channel() == listener) {
                            throw e; // A listener error is fatal to this server instance.
                        }
                        System.err.println("I/O error on client connection: " + e.getMessage());
                        closeKey(key);
                    } catch (RuntimeException e) {
                        System.err.println("Unexpected client event error: " + e.getMessage());
                        closeKey(key);
                    }
                }
            }
        }
    }

    private void acceptReadyClients(ServerSocketChannel listener, Selector selector) throws IOException {
        SocketChannel client;
        // accept() is non-blocking: drain all connections currently ready.
        while ((client = listener.accept()) != null) {
            try {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                ConnectionState state = new ConnectionState();
                client.register(selector, SelectionKey.OP_READ, state);
                System.out.println("Accepted NIO connection from " + client.getRemoteAddress());
            } catch (IOException | RuntimeException e) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                throw e;
            }
        }
    }

    private void readFromClient(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        while (true) {
            state.readBuffer.clear();
            int bytesRead = client.read(state.readBuffer);

            if (bytesRead == -1) {
                closeKey(key);
                return;
            }
            if (bytesRead == 0) {
                return; // No more bytes are available right now; return to the selector.
            }

            state.readBuffer.flip();
            byte[] bytes = new byte[state.readBuffer.remaining()];
            state.readBuffer.get(bytes);
            state.incoming.write(bytes, 0, bytes.length);

            processCompleteRequests(key, state);
            if (!key.isValid() || state.closeAfterWrite) {
                return;
            }
        }
    }

    /**
     * Processes zero or more complete header blocks from this connection's buffer.
     * If the request is incomplete, its bytes remain in state.incoming for the next
     * OP_READ event. Extra bytes after one request are retained for the next request.
     */
    private void processCompleteRequests(SelectionKey key, ConnectionState state) throws IOException {
        while (!state.closeAfterWrite) {
            byte[] pending = state.incoming.toByteArray();
            int headerEnd = findHeaderEnd(pending);

            if (headerEnd < 0) {
                if (pending.length > MAX_HEADER_BYTES) {
                    queueError(key, state, "431 Request Header Fields Too Large", false,
                            true, null);
                }
                return; // Partial request: preserve state and wait for the next read event.
            }

            if (headerEnd > MAX_HEADER_BYTES) {
                queueError(key, state, "431 Request Header Fields Too Large", false,
                        true, null);
                return;
            }

            String rawHeaders = new String(pending, 0, headerEnd, HEADER_CHARSET);
            int bytesAfterThisRequest = headerEnd + HEADER_END.length;
            replacePendingBytes(state, pending, bytesAfterThisRequest,
                    pending.length - bytesAfterThisRequest);

            HttpRequest request;
            try {
                // The team's current parser method is parser(...), per HttpRequestParser.java.
                // Interface.md currently documents parse(...); coordinate a rename with the team.
                request = HttpRequestParser.parser(rawHeaders);
            } catch (IllegalArgumentException e) {
                queueError(key, state, "400 Bad Request", false, true, null);
                return;
            }

            String method = request.getMethod();
            boolean headOnly = "HEAD".equals(method);
            boolean closeRequested = hasConnectionClose(request.getHeader("Connection"));

            if (!"HTTP/1.1".equals(request.getVersion())) {
                queueError(key, state, "505 HTTP Version Not Supported", headOnly,
                        true, null);
                return;
            }

            if (request.getPath().length() > MAX_REQUEST_TARGET_CHARS) {
                queueError(key, state, "414 URI Too Long", headOnly, true, null);
                return;
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                queueError(key, state, "405 Method Not Allowed", false,
                        true, "Allow: GET, HEAD\r\n");
                return;
            }

            File file = fileHandler.resolveFile(request.getPath());
            if (file == null) {
                queueError(key, state, "404 Not Found", headOnly, closeRequested, null);
                if (closeRequested) {
                    return;
                }
                continue;
            }

            byte[] body;
            try {
                body = fileHandler.readFile(file);
            } catch (IOException e) {
                System.err.println("Unable to read requested file: " + e.getMessage());
                queueError(key, state, "500 Internal Server Error", headOnly,
                        closeRequested, null);
                if (closeRequested) {
                    return;
                }
                continue;
            }

            queueResponse(key, state, "200 OK", fileHandler.getMimeType(file), body,
                    headOnly, closeRequested, null);
            if (closeRequested) {
                return;
            }
            // If the same read delivered another full request, handle it in order now.
        }
    }

    private void writeToClient(SelectionKey key) throws IOException {
        SocketChannel client = (SocketChannel) key.channel();
        ConnectionState state = (ConnectionState) key.attachment();

        while (!state.pendingWrites.isEmpty()) {
            ByteBuffer next = state.pendingWrites.peek();
            client.write(next);
            if (next.hasRemaining()) {
                return; // Partial write: keep OP_WRITE enabled and resume on a later event.
            }
            state.pendingWrites.remove();
        }

        int interests = key.interestOps() & ~SelectionKey.OP_WRITE;
        if (state.closeAfterWrite) {
            key.interestOps(interests & ~SelectionKey.OP_READ);
            closeKey(key);
        } else {
            key.interestOps(interests | SelectionKey.OP_READ);
        }
    }

    private void queueError(SelectionKey key, ConnectionState state, String statusLine,
                            boolean headOnly, boolean closeConnection, String extraHeaders) {
        byte[] body = (statusLine + "\n").getBytes(RESPONSE_CHARSET);
        queueResponse(key, state, statusLine, "text/plain; charset=utf-8", body,
                headOnly, closeConnection, extraHeaders);
    }

    private void queueResponse(SelectionKey key, ConnectionState state, String statusLine,
                               String contentType, byte[] body, boolean headOnly,
                               boolean closeConnection, String extraHeaders) {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(statusLine).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: ").append(closeConnection ? "close" : "keep-alive").append("\r\n");
        if (extraHeaders != null) {
            header.append(extraHeaders);
        }
        header.append("\r\n");

        byte[] headerBytes = header.toString().getBytes(StandardCharsets.US_ASCII);
        int bodyBytesToSend = headOnly ? 0 : body.length;
        ByteBuffer response = ByteBuffer.allocate(headerBytes.length + bodyBytesToSend);
        response.put(headerBytes);
        if (bodyBytesToSend > 0) {
            response.put(body, 0, bodyBytesToSend);
        }
        response.flip();
        state.pendingWrites.add(response);

        if (closeConnection) {
            state.closeAfterWrite = true;
        }

        // Listen for write readiness only when there's data waiting to be sent.
        int interests = key.interestOps() | SelectionKey.OP_WRITE;
        if (state.closeAfterWrite) {
            interests &= ~SelectionKey.OP_READ;
        }
        key.interestOps(interests);
    }

    private static boolean hasConnectionClose(String connectionHeader) {
        if (connectionHeader == null) {
            return false; // HTTP/1.1 defaults to a persistent connection.
        }
        for (String token : connectionHeader.split(",")) {
            if ("close".equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int findHeaderEnd(byte[] bytes) {
        for (int i = 0; i <= bytes.length - HEADER_END.length; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n'
                    && bytes[i + 2] == '\r' && bytes[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void replacePendingBytes(ConnectionState state, byte[] bytes,
                                            int offset, int length) {
        state.incoming.reset();
        if (length > 0) {
            state.incoming.write(bytes, offset, length);
        }
    }

    private static void closeKey(SelectionKey key) {
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
    }

    private static final class ConnectionState {
        private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
        private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
        private final Deque<ByteBuffer> pendingWrites = new ArrayDeque<>();
        private boolean closeAfterWrite;
    }
}

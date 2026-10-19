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

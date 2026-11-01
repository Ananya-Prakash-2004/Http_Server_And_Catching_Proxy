package httpserver;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

final class OriginPool {
    /** Origins close idle connections (the team's server after 5 s), so do not trust older ones. */
    private static final long MAX_IDLE_MS = 4_000;
    private static final int MAX_IDLE_PER_ORIGIN = 64;

    private static final class Idle {
        final Socket socket;
        final long since;

        Idle(Socket socket, long since) {
            this.socket = socket;
            this.since = since;
        }
    }

    private final Map<String, Deque<Idle>> idle = new HashMap<>();

    
    synchronized Socket borrow(String host, int port) {
        Deque<Idle> d = idle.get(key(host, port));
        long now = System.currentTimeMillis();
        while (d != null && !d.isEmpty()) {
            Idle i = d.pollFirst(); // most recently used first
            if (now - i.since <= MAX_IDLE_MS && !i.socket.isClosed()) {
                return i.socket;
            }
            closeQuietly(i.socket);
        }
        return null;
    }

   
    synchronized void release(String host, int port, Socket socket) {
        long now = System.currentTimeMillis();
        Deque<Idle> d = idle.computeIfAbsent(key(host, port), k -> new ArrayDeque<>());
        d.addFirst(new Idle(socket, now));
        while (!d.isEmpty() && (d.size() > MAX_IDLE_PER_ORIGIN || now - d.peekLast().since > MAX_IDLE_MS)) {
            closeQuietly(d.pollLast().socket); // drop the oldest
        }
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    private static String key(String host, int port) {
        return host.toLowerCase() + ":" + port;
    }
}

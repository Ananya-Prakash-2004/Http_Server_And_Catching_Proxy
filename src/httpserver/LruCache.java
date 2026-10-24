package httpserver;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe LRU cache with O(1) get / put / remove.
 *
 * Design: a HashMap gives O(1) key -> node lookup, and a doubly-linked list keeps
 * the entries ordered by recency. The head side is the most recently used entry,
 * the tail side is the least recently used (the next eviction victim). Sentinel
 * head/tail nodes mean we never special-case "empty list" or "first/last node".
 */
public class LruCache<K, V> {

    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head = new Node<>(null, null); // sentinel: most-recent side
    private final Node<K, V> tail = new Node<>(null, null); // sentinel: least-recent side

    private long hits;
    private long misses;
    private long evictions;

    public LruCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.map = new HashMap<>(capacity * 2);
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the value and marks it most recently used, or null if absent. */
    public synchronized V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            misses++;
            return null;
        }
        hits++;
        unlink(node);
        addFirst(node);
        return node.value;
    }

    /** Inserts or replaces; evicts the least recently used entry if over capacity. */
    public synchronized void put(K key, V value) {
        Node<K, V> existing = map.get(key);
        if (existing != null) {
            existing.value = value;
            unlink(existing);
            addFirst(existing);
            return;
        }
        Node<K, V> node = new Node<>(key, value);
        map.put(key, node);
        addFirst(node);
        if (map.size() > capacity) {
            Node<K, V> lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);
            evictions++;
        }
    }

    public synchronized V remove(K key) {
        Node<K, V> node = map.remove(key);
        if (node == null) {
            return null;
        }
        unlink(node);
        return node.value;
    }

    public synchronized int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized void clear() {
        map.clear();
        head.next = tail;
        tail.prev = head;
    }

    public synchronized String stats() {
        long total = hits + misses;
        double ratio = total == 0 ? 0.0 : (100.0 * hits / total);
        return String.format("size=%d/%d hits=%d misses=%d evictions=%d lookup-hit-ratio=%.1f%%",
                map.size(), capacity, hits, misses, evictions, ratio);
    }

    // --- linked-list helpers (callers hold the lock) ---

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void addFirst(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }
}
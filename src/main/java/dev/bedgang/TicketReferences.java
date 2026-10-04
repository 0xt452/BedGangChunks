package dev.bedgang;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** A plugin owns only one Paper ticket per chunk, even when loaders overlap. */
final class TicketReferences<K> {
    private final Map<K, Integer> counts = new HashMap<>();
    boolean retain(K key) { return counts.merge(key, 1, Integer::sum) == 1; }
    boolean release(K key) {
        Integer count = counts.get(key);
        if (count == null) return false;
        if (count > 1) { counts.put(key, count - 1); return false; }
        counts.remove(key);
        return true;
    }
    boolean contains(K key) { return counts.containsKey(key); }
    Set<K> keys() { return Set.copyOf(counts.keySet()); }
    void clear() { counts.clear(); }
}

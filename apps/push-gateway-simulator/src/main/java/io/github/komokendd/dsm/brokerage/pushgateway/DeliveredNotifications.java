package io.github.komokendd.dsm.brokerage.pushgateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded record of notification ids already pushed to devices. Like a real provider's
 * idempotency window it forgets the least recently seen ids once full; the window is far
 * larger than any retry or redrive delay in the demo.
 */
final class DeliveredNotifications {
    private final Map<String, Boolean> seen;

    DeliveredNotifications(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.seen = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Returns {@code true} only the first time an id is seen within the window. */
    synchronized boolean markDelivered(String notificationId) {
        return seen.putIfAbsent(notificationId, Boolean.TRUE) == null;
    }

    synchronized int size() {
        return seen.size();
    }
}

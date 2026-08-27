package io.github.komokendd.dsm.fx.common;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class EventIds {
    private EventIds() {
    }

    public static String derived(String sourceEventId, String eventType) {
        String source = requireText(sourceEventId, "sourceEventId");
        String type = requireText(eventType, "eventType");
        return UUID.nameUUIDFromBytes(
                        ("datadog-dsm-demo:" + type + ":" + source).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}

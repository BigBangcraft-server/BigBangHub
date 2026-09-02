package com.bigbangcraft.hub.common;

import java.util.Objects;

public record ActionDefinition(ActionType type, String value) {
    public ActionDefinition {
        type = Objects.requireNonNull(type, "type");
        value = Objects.requireNonNull(value, "value").trim();
        if (type != ActionType.CLOSE && value.isEmpty()) {
            throw new IllegalArgumentException("Action value is empty for " + type);
        }
    }
}

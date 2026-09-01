package com.bigbangcraft.hub.api;

import java.util.Objects;

public record TransferResult(boolean success, String message) {
    public TransferResult {
        message = Objects.requireNonNull(message, "message");
    }

    public static TransferResult success(String message) {
        return new TransferResult(true, message);
    }

    public static TransferResult failure(String message) {
        return new TransferResult(false, message);
    }
}

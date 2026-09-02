package com.bigbangcraft.hub.common;

public final class ProtocolValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProtocolValidationException(String message) {
        super(message);
    }

    public ProtocolValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

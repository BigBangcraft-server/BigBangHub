package com.bigbangcraft.hub.api;

public class MatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum ErrorCode {
        MATCH_NOT_FOUND,
        INVALID_TRANSITION,
        MATCH_FULL,
        MATCH_LOCKED,
        ADMISSION_EXPIRED,
        INSTANCE_UNAVAILABLE,
        PLAYER_ALREADY_ASSIGNED,
        STALE_REVISION,
        ACTIVE_MATCH_EXISTS,
        NO_ACTIVE_MATCH,
        UNAUTHORIZED,
        DIRECT_JOIN_NOT_PERMITTED,
        ALREADY_FINISHED
    }

    private final ErrorCode errorCode;

    public MatchException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MatchException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

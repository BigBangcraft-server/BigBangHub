package com.bigbangcraft.hub.api;

public class PartyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum ErrorCode {
        PARTY_NOT_FOUND,
        PLAYER_ALREADY_IN_PARTY,
        PLAYER_NOT_IN_PARTY,
        NOT_PARTY_LEADER,
        PARTY_FULL,
        CANNOT_INVITE_SELF,
        TARGET_ALREADY_IN_PARTY,
        INVITE_ALREADY_PENDING,
        INVITE_NOT_FOUND,
        INVITE_EXPIRED,
        TARGET_NOT_IN_PARTY,
        CANNOT_KICK_LEADER,
        CANNOT_TRANSFER_TO_SELF,
        INVALID_PARTY_STATE,
        PARTY_MUTATION_LOCKED,
        RATE_LIMITED
    }

    private final ErrorCode errorCode;

    public PartyException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PartyException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

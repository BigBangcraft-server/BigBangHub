package com.bigbangcraft.hub.common;

public enum MessageType {
    QUEUE_JOIN(1),
    QUEUE_LEAVE(2),
    QUEUE_STATUS(3),
    SERVER_CONNECT(4),
    QUEUE_RESPONSE(5),
    SERVER_RESPONSE(6),
    SERVER_STATUS(7),
    INSTANCE_REGISTER(8),
    INSTANCE_HEARTBEAT(9),
    INSTANCE_UNREGISTER(10),
    INSTANCE_STATE_CHANGE(11),
    INSTANCE_REGISTER_ACK(12),
    MATCH_CREATE(13),
    MATCH_CREATE_ACK(14),
    MATCH_STATE_CHANGE(15),
    MATCH_STATE_ACK(16),
    ADMISSION_REQUEST(17),
    ADMISSION_RESPONSE(18),
    PARTICIPANT_STATE_CHANGE(19),
    MATCH_FINISH(20),
    MATCH_ABORT(21),
    INSTANCE_READY(22),
    PLAYER_RETURN(23),
    PARTY_CREATE(24),
    PARTY_INVITE(25),
    PARTY_ACCEPT(26),
    PARTY_DECLINE(27),
    PARTY_LEAVE(28),
    PARTY_KICK(29),
    PARTY_LEADER_CHANGE(30),
    PARTY_DISBAND(31),
    PARTY_SYNC(32),
    PARTY_RESPONSE(33);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static MessageType fromCode(int code) throws ProtocolValidationException {
        for (MessageType type : values()) {
            if (type.code == code) return type;
        }
        throw new ProtocolValidationException("Unknown message type: " + code);
    }
}

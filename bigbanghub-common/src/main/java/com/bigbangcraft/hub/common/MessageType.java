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
    INSTANCE_REGISTER_ACK(12);

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

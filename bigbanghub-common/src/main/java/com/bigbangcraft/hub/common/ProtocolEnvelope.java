package com.bigbangcraft.hub.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record ProtocolEnvelope(int protocolVersion, MessageType messageType, UUID correlationId, byte[] payload) {
    public ProtocolEnvelope {
        if (protocolVersion != ProtocolCodec.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + protocolVersion);
        }
        messageType = Objects.requireNonNull(messageType, "messageType");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        payload = Objects.requireNonNull(payload, "payload").clone();
        if (payload.length > ProtocolCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload is too large");
        }
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ProtocolEnvelope that)) return false;
        return protocolVersion == that.protocolVersion
                && messageType == that.messageType
                && correlationId.equals(that.correlationId)
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocolVersion, messageType, correlationId, Arrays.hashCode(payload));
    }
}

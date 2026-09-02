package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {
    @Test
    void roundTripsVersionedEnvelopeAndPayload() throws Exception {
        ProtocolCodec codec = new ProtocolCodec("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ProtocolCodec.MAX_PAYLOAD_BYTES, true);
        UUID correlation = UUID.randomUUID();
        byte[] payload = MessagePayloads.queueJoin(correlation, GameId.of("campominado"));
        ProtocolEnvelope envelope = new ProtocolEnvelope(1, MessageType.QUEUE_JOIN, correlation, payload);

        ProtocolEnvelope decoded = codec.decode(codec.encode(envelope));
        assertEquals(envelope, decoded);
        assertEquals(GameId.of("campominado"), MessagePayloads.queueJoin(decoded.payload()).gameId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void rejectsUnknownVersionTruncationAndBadAuthentication() {
        ProtocolCodec codec = new ProtocolCodec("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ProtocolCodec.MAX_PAYLOAD_BYTES, true);
        byte[] encoded = codec.encode(new ProtocolEnvelope(1, MessageType.QUEUE_STATUS, UUID.randomUUID(),
                MessagePayloads.playerRequest(UUID.randomUUID())));
        byte[] unknownVersion = encoded.clone();
        unknownVersion[4] = 2;
        byte[] badAuth = encoded.clone();
        badAuth[badAuth.length - 1] ^= 1;

        assertThrows(ProtocolValidationException.class, () -> codec.decode(unknownVersion));
        assertThrows(ProtocolValidationException.class, () -> codec.decode(java.util.Arrays.copyOf(encoded, 10)));
        assertThrows(ProtocolValidationException.class, () -> codec.decode(badAuth));
    }
}

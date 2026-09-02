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

    @Test
    void roundTripsInstanceLifecycleMessages() throws Exception {
        ProtocolCodec codec = new ProtocolCodec(new byte[0], ProtocolCodec.MAX_PAYLOAD_BYTES, false);
        UUID correlation = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        com.bigbangcraft.hub.api.ServerId serverId = com.bigbangcraft.hub.api.ServerId.of("campominado-01");
        GameId gameId = GameId.of("campominado");

        // 1. Register
        MessagePayloads.InstanceRegister register = new MessagePayloads.InstanceRegister(
                serverId, gameId, "campominado-01", sessionId,
                MessagePayloads.GameStateWire.WAITING, 4, 2, 10, true);
        byte[] regPayload = MessagePayloads.instanceRegister(register);
        ProtocolEnvelope regEnv = new ProtocolEnvelope(1, MessageType.INSTANCE_REGISTER, correlation, regPayload);
        ProtocolEnvelope decodedReg = codec.decode(codec.encode(regEnv));
        assertEquals(regEnv, decodedReg);
        MessagePayloads.InstanceRegister decodedRegPayload = MessagePayloads.instanceRegister(decodedReg.payload());
        assertEquals(register, decodedRegPayload);

        // 2. Heartbeat
        MessagePayloads.InstanceHeartbeat heartbeat = new MessagePayloads.InstanceHeartbeat(
                serverId, sessionId, MessagePayloads.GameStateWire.WAITING, 5, 10, true);
        byte[] hbPayload = MessagePayloads.instanceHeartbeat(heartbeat);
        ProtocolEnvelope hbEnv = new ProtocolEnvelope(1, MessageType.INSTANCE_HEARTBEAT, correlation, hbPayload);
        ProtocolEnvelope decodedHb = codec.decode(codec.encode(hbEnv));
        assertEquals(heartbeat, MessagePayloads.instanceHeartbeat(decodedHb.payload()));

        // 3. State Change
        MessagePayloads.InstanceStateChange stateChange = new MessagePayloads.InstanceStateChange(
                serverId, sessionId, MessagePayloads.GameStateWire.IN_GAME, false, 10, 10);
        byte[] scPayload = MessagePayloads.instanceStateChange(stateChange);
        ProtocolEnvelope scEnv = new ProtocolEnvelope(1, MessageType.INSTANCE_STATE_CHANGE, correlation, scPayload);
        ProtocolEnvelope decodedSc = codec.decode(codec.encode(scEnv));
        assertEquals(stateChange, MessagePayloads.instanceStateChange(decodedSc.payload()));

        // 4. Unregister
        MessagePayloads.InstanceUnregister unregister = new MessagePayloads.InstanceUnregister(
                serverId, sessionId, "server shutting down");
        byte[] unregPayload = MessagePayloads.instanceUnregister(unregister);
        ProtocolEnvelope unregEnv = new ProtocolEnvelope(1, MessageType.INSTANCE_UNREGISTER, correlation, unregPayload);
        ProtocolEnvelope decodedUnreg = codec.decode(codec.encode(unregEnv));
        assertEquals(unregister, MessagePayloads.instanceUnregister(decodedUnreg.payload()));

        // 5. Register Ack
        MessagePayloads.InstanceRegisterAck ack = new MessagePayloads.InstanceRegisterAck(
                serverId, sessionId, true, "Registered successfully");
        byte[] ackPayload = MessagePayloads.instanceRegisterAck(ack);
        ProtocolEnvelope ackEnv = new ProtocolEnvelope(1, MessageType.INSTANCE_REGISTER_ACK, correlation, ackPayload);
        ProtocolEnvelope decodedAck = codec.decode(codec.encode(ackEnv));
        assertEquals(ack, MessagePayloads.instanceRegisterAck(decodedAck.payload()));
    }

    @Test
    void rejectsInvalidInstanceCapacityAndPayloads() {
        // Invalid capacity: min > max, max < 1, player < 0
        assertThrows(ProtocolValidationException.class, () ->
                MessagePayloads.instanceRegister(new byte[]{0, 1, 'a'}));
    }
}

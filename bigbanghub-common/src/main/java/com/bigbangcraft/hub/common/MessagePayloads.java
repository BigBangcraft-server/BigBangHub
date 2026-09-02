package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.QueueStatus;
import com.bigbangcraft.hub.api.ServerId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

public final class MessagePayloads {
    private static final int MAX_TEXT_BYTES = 256;

    private MessagePayloads() { }

    public record QueueJoin(UUID playerId, GameId gameId) { }
    public record PlayerRequest(UUID playerId) { }
    public record ServerConnect(UUID playerId, ServerId serverId) { }
    public record ServerStatus(ServerId serverId, GameStateWire state, int playerCount, int maxPlayers) { }
    public record QueueResponse(UUID playerId, QueueResult.Code code, Optional<GameId> game,
                                int position, int size, String message) { }
    public record ServerResponse(UUID playerId, boolean success, String message) { }
    public record InstanceRegister(ServerId instanceId, GameId gameId, String serverName, UUID sessionId,
                                  GameStateWire state, int playerCount, int minPlayers, int maxPlayers,
                                  boolean acceptingPlayers) { }
    public record InstanceHeartbeat(ServerId instanceId, UUID sessionId, GameStateWire state,
                                    int playerCount, int maxPlayers, boolean acceptingPlayers) { }
    public record InstanceUnregister(ServerId instanceId, UUID sessionId, String reason) { }
    public record InstanceStateChange(ServerId instanceId, UUID sessionId, GameStateWire state,
                                      boolean acceptingPlayers, int playerCount, int maxPlayers) { }
    public record InstanceRegisterAck(ServerId instanceId, UUID sessionId, boolean success, String message) { }

    public enum GameStateWire { OFFLINE, STARTING, WAITING, STARTING_GAME, IN_GAME, ENDING, FULL, MAINTENANCE }

    public static byte[] queueJoin(UUID playerId, GameId gameId) {
        return write(out -> { uuid(out, playerId); text(out, gameId.value()); });
    }

    public static QueueJoin queueJoin(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new QueueJoin(uuid(input), GameId.of(text(input))), "queue join");
    }

    public static byte[] playerRequest(UUID playerId) {
        return write(out -> uuid(out, playerId));
    }

    public static PlayerRequest playerRequest(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PlayerRequest(uuid(input)), "player request");
    }

    public static byte[] serverConnect(UUID playerId, ServerId serverId) {
        return write(out -> { uuid(out, playerId); text(out, serverId.value()); });
    }

    public static ServerConnect serverConnect(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new ServerConnect(uuid(input), ServerId.of(text(input))), "server connect");
    }

    public static byte[] serverStatus(ServerId serverId, GameStateWire state, int playerCount, int maxPlayers) {
        return write(out -> {
            text(out, serverId.value());
            out.writeByte(state.ordinal());
            out.writeInt(playerCount);
            out.writeInt(maxPlayers);
        });
    }

    public static ServerStatus serverStatus(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId server = ServerId.of(text(input));
            int state = input.readUnsignedByte();
            if (state >= GameStateWire.values().length) throw new IOException("Invalid state");
            int playerCount = input.readInt();
            int maxPlayers = input.readInt();
            if (playerCount < 0 || maxPlayers < 1 || playerCount > maxPlayers) throw new IOException("Invalid capacity");
            return new ServerStatus(server, GameStateWire.values()[state], playerCount, maxPlayers);
        }, "server status");
    }

    public static byte[] queueResponse(QueueResponse response) {
        return write(out -> {
            uuid(out, response.playerId());
            out.writeByte(response.code().ordinal());
            out.writeBoolean(response.game().isPresent());
            if (response.game().isPresent()) uncheckedText(out, response.game().orElseThrow().value());
            out.writeInt(response.position());
            out.writeInt(response.size());
            uncheckedText(out, response.message());
        });
    }

    public static QueueResponse queueResponse(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID player = uuid(input);
            int code = input.readUnsignedByte();
            if (code >= QueueResult.Code.values().length) throw new IOException("Invalid queue result");
            Optional<GameId> game = input.readBoolean() ? Optional.of(GameId.of(text(input))) : Optional.empty();
            int position = input.readInt();
            int size = input.readInt();
            String message = text(input);
            if (position < 0 || size < 0) throw new IOException("Invalid queue counters");
            return new QueueResponse(player, QueueResult.Code.values()[code], game, position, size, message);
        }, "queue response");
    }

    public static byte[] serverResponse(ServerResponse response) {
        return write(out -> { uuid(out, response.playerId()); out.writeBoolean(response.success()); uncheckedText(out, response.message()); });
    }

    public static ServerResponse serverResponse(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new ServerResponse(uuid(input), input.readBoolean(), text(input)), "server response");
    }

    public static byte[] instanceRegister(InstanceRegister register) {
        return write(out -> {
            text(out, register.instanceId().value());
            text(out, register.gameId().value());
            text(out, register.serverName());
            uuid(out, register.sessionId());
            out.writeByte(register.state().ordinal());
            out.writeInt(register.playerCount());
            out.writeInt(register.minPlayers());
            out.writeInt(register.maxPlayers());
            out.writeBoolean(register.acceptingPlayers());
        });
    }

    public static InstanceRegister instanceRegister(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            GameId gameId = GameId.of(text(input));
            String serverName = text(input);
            if (serverName.isBlank() || serverName.length() > 64) throw new IOException("Invalid server name");
            UUID sessionId = uuid(input);
            int state = input.readUnsignedByte();
            if (state >= GameStateWire.values().length) throw new IOException("Invalid state");
            int playerCount = input.readInt();
            int minPlayers = input.readInt();
            int maxPlayers = input.readInt();
            boolean acceptingPlayers = input.readBoolean();
            if (playerCount < 0 || minPlayers < 0 || maxPlayers < 1 || maxPlayers > 1000 || minPlayers > maxPlayers) {
                throw new IOException("Invalid capacity values");
            }
            return new InstanceRegister(instanceId, gameId, serverName, sessionId,
                    GameStateWire.values()[state], playerCount, minPlayers, maxPlayers, acceptingPlayers);
        }, "instance register");
    }

    public static byte[] instanceHeartbeat(InstanceHeartbeat heartbeat) {
        return write(out -> {
            text(out, heartbeat.instanceId().value());
            uuid(out, heartbeat.sessionId());
            out.writeByte(heartbeat.state().ordinal());
            out.writeInt(heartbeat.playerCount());
            out.writeInt(heartbeat.maxPlayers());
            out.writeBoolean(heartbeat.acceptingPlayers());
        });
    }

    public static InstanceHeartbeat instanceHeartbeat(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            int state = input.readUnsignedByte();
            if (state >= GameStateWire.values().length) throw new IOException("Invalid state");
            int playerCount = input.readInt();
            int maxPlayers = input.readInt();
            boolean acceptingPlayers = input.readBoolean();
            if (playerCount < 0 || maxPlayers < 1 || maxPlayers > 1000) {
                throw new IOException("Invalid capacity values");
            }
            return new InstanceHeartbeat(instanceId, sessionId, GameStateWire.values()[state],
                    playerCount, maxPlayers, acceptingPlayers);
        }, "instance heartbeat");
    }

    public static byte[] instanceUnregister(InstanceUnregister unregister) {
        return write(out -> {
            text(out, unregister.instanceId().value());
            uuid(out, unregister.sessionId());
            text(out, unregister.reason());
        });
    }

    public static InstanceUnregister instanceUnregister(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            String reason = text(input);
            return new InstanceUnregister(instanceId, sessionId, reason);
        }, "instance unregister");
    }

    public static byte[] instanceStateChange(InstanceStateChange stateChange) {
        return write(out -> {
            text(out, stateChange.instanceId().value());
            uuid(out, stateChange.sessionId());
            out.writeByte(stateChange.state().ordinal());
            out.writeBoolean(stateChange.acceptingPlayers());
            out.writeInt(stateChange.playerCount());
            out.writeInt(stateChange.maxPlayers());
        });
    }

    public static InstanceStateChange instanceStateChange(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            int state = input.readUnsignedByte();
            if (state >= GameStateWire.values().length) throw new IOException("Invalid state");
            boolean acceptingPlayers = input.readBoolean();
            int playerCount = input.readInt();
            int maxPlayers = input.readInt();
            if (playerCount < 0 || maxPlayers < 1 || maxPlayers > 1000) {
                throw new IOException("Invalid capacity values");
            }
            return new InstanceStateChange(instanceId, sessionId, GameStateWire.values()[state],
                    acceptingPlayers, playerCount, maxPlayers);
        }, "instance state change");
    }

    public static byte[] instanceRegisterAck(InstanceRegisterAck ack) {
        return write(out -> {
            text(out, ack.instanceId().value());
            uuid(out, ack.sessionId());
            out.writeBoolean(ack.success());
            text(out, ack.message());
        });
    }

    public static InstanceRegisterAck instanceRegisterAck(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            boolean success = input.readBoolean();
            String message = text(input);
            return new InstanceRegisterAck(instanceId, sessionId, success, message);
        }, "instance register ack");
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private interface Reader<T> { T read(DataInputStream input) throws IOException; }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode payload", exception);
        }
    }

    private static <T> T read(byte[] payload, Reader<T> reader, String name) throws ProtocolValidationException {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            T value = reader.read(input);
            if (input.available() != 0) throw new ProtocolValidationException("Trailing " + name + " data");
            return value;
        } catch (IllegalArgumentException | IOException exception) {
            throw new ProtocolValidationException("Malformed " + name + " payload", exception);
        }
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IOException("Text is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static void uncheckedText(DataOutputStream out, String value) throws IOException {
        text(out, value);
    }

    private static String text(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_TEXT_BYTES) throw new IOException("Text is too long");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated text");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

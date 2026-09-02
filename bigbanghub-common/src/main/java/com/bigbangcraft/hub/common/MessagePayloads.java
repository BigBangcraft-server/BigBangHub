package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.ServerId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MessagePayloads {
    private static final int MAX_TEXT_BYTES = 256;
    private static final int MAX_METADATA_BYTES = 1024;

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

    // Match Lifecycle Payloads
    public record MatchCreate(ServerId instanceId, UUID sessionId, MatchId matchId, GameId gameId,
                              int minPlayers, int maxPlayers, boolean allowLateJoin, String arenaId) { }
    public record MatchCreateAck(MatchId matchId, boolean success, long revision, String message) { }
    public record MatchStateChange(ServerId instanceId, UUID sessionId, MatchId matchId,
                                  long revision, MatchStateWire state) { }
    public record MatchStateAck(MatchId matchId, long revision, MatchStateWire state,
                               boolean success, String message) { }
    public record AdmissionRequest(UUID ticketId, UUID playerId, MatchId matchId,
                                  ServerId instanceId, String token) { }
    public record AdmissionResponse(UUID ticketId, UUID playerId, MatchId matchId,
                                   boolean accepted, ParticipantRoleWire role, String reason) { }
    public record ParticipantStateChange(MatchId matchId, UUID playerId,
                                         ParticipantRoleWire role, ParticipantStateWire state) { }
    public record MatchFinish(ServerId instanceId, UUID sessionId, MatchId matchId, long revision,
                              MatchResultOutcomeWire outcome, long durationMillis,
                              List<UUID> winnerIds, String metadataString) { }
    public record MatchAbort(ServerId instanceId, UUID sessionId, MatchId matchId,
                             long revision, String reason) { }
    public record InstanceReady(ServerId instanceId, UUID sessionId, MatchId matchId) { }
    public record PlayerReturn(UUID playerId, ReturnReasonWire reason, String message) { }

    // Party Lifecycle Payloads
    public record PartyCreate(UUID leaderId) { }
    public record PartyInvitePayload(UUID actorId, UUID targetId, String targetName) { }
    public record PartyAcceptPayload(UUID playerId, Optional<PartyId> partyId) { }
    public record PartyDeclinePayload(UUID playerId, Optional<PartyId> partyId) { }
    public record PartyLeavePayload(UUID playerId) { }
    public record PartyKickPayload(UUID actorId, UUID targetId) { }
    public record PartyLeaderChangePayload(UUID actorId, UUID newLeaderId) { }
    public record PartyDisbandPayload(UUID actorId, PartyId partyId) { }
    public record PartySyncPayload(PartyId partyId, UUID leaderId, List<UUID> members, PartyStateWire state, long revision) { }
    public record PartyResponsePayload(UUID playerId, boolean success, String message, Optional<PartyId> partyId) { }

    public enum GameStateWire { OFFLINE, STARTING, WAITING, STARTING_GAME, IN_GAME, ENDING, FULL, MAINTENANCE }
    public enum MatchStateWire { CREATED, WAITING, COUNTDOWN, LOCKED, IN_GAME, ENDING, FINISHED, ABORTED }
    public enum ParticipantRoleWire { PLAYER, SPECTATOR }
    public enum ParticipantStateWire { RESERVED, ADMITTED, ACTIVE, ELIMINATED, SPECTATING, LEAVING, LEFT, DISCONNECTED }
    public enum MatchResultOutcomeWire { WIN, DRAW, ABORTED }
    public enum ReturnReasonWire { MATCH_FINISHED, MATCH_ABORTED, PLAYER_ELIMINATED, PLAYER_LEFT, SERVER_FAILURE, ADMIN_FORCE_RETURN, DIRECT_JOIN_REJECTED }
    public enum PartyStateWire { IDLE, QUEUED, ASSIGNED, IN_MATCH, DISBANDING }

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

    // MATCH LIFECYCLE ENCODERS / DECODERS

    public static byte[] matchCreate(MatchCreate create) {
        return write(out -> {
            text(out, create.instanceId().value());
            uuid(out, create.sessionId());
            text(out, create.matchId().value());
            text(out, create.gameId().value());
            out.writeInt(create.minPlayers());
            out.writeInt(create.maxPlayers());
            out.writeBoolean(create.allowLateJoin());
            text(out, create.arenaId() != null ? create.arenaId() : "");
        });
    }

    public static MatchCreate matchCreate(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            GameId gameId = GameId.of(text(input));
            int minPlayers = input.readInt();
            int maxPlayers = input.readInt();
            boolean allowLateJoin = input.readBoolean();
            String arenaId = text(input);
            if (minPlayers < 1 || maxPlayers < minPlayers || maxPlayers > 1000) {
                throw new IOException("Invalid match capacity");
            }
            return new MatchCreate(instanceId, sessionId, matchId, gameId, minPlayers, maxPlayers, allowLateJoin, arenaId);
        }, "match create");
    }

    public static byte[] matchCreateAck(MatchCreateAck ack) {
        return write(out -> {
            text(out, ack.matchId().value());
            out.writeBoolean(ack.success());
            out.writeLong(ack.revision());
            text(out, ack.message());
        });
    }

    public static MatchCreateAck matchCreateAck(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            MatchId matchId = MatchId.of(text(input));
            boolean success = input.readBoolean();
            long revision = input.readLong();
            String message = text(input);
            return new MatchCreateAck(matchId, success, revision, message);
        }, "match create ack");
    }

    public static byte[] matchStateChange(MatchStateChange change) {
        return write(out -> {
            text(out, change.instanceId().value());
            uuid(out, change.sessionId());
            text(out, change.matchId().value());
            out.writeLong(change.revision());
            out.writeByte(change.state().ordinal());
        });
    }

    public static MatchStateChange matchStateChange(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            long revision = input.readLong();
            int stateOrd = input.readUnsignedByte();
            if (stateOrd >= MatchStateWire.values().length) throw new IOException("Invalid match state");
            return new MatchStateChange(instanceId, sessionId, matchId, revision, MatchStateWire.values()[stateOrd]);
        }, "match state change");
    }

    public static byte[] matchStateAck(MatchStateAck ack) {
        return write(out -> {
            text(out, ack.matchId().value());
            out.writeLong(ack.revision());
            out.writeByte(ack.state().ordinal());
            out.writeBoolean(ack.success());
            text(out, ack.message());
        });
    }

    public static MatchStateAck matchStateAck(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            MatchId matchId = MatchId.of(text(input));
            long revision = input.readLong();
            int stateOrd = input.readUnsignedByte();
            if (stateOrd >= MatchStateWire.values().length) throw new IOException("Invalid match state");
            boolean success = input.readBoolean();
            String message = text(input);
            return new MatchStateAck(matchId, revision, MatchStateWire.values()[stateOrd], success, message);
        }, "match state ack");
    }

    public static byte[] admissionRequest(AdmissionRequest request) {
        return write(out -> {
            uuid(out, request.ticketId());
            uuid(out, request.playerId());
            text(out, request.matchId().value());
            text(out, request.instanceId().value());
            text(out, request.token());
        });
    }

    public static AdmissionRequest admissionRequest(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID ticketId = uuid(input);
            UUID playerId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            ServerId instanceId = ServerId.of(text(input));
            String token = text(input);
            return new AdmissionRequest(ticketId, playerId, matchId, instanceId, token);
        }, "admission request");
    }

    public static byte[] admissionResponse(AdmissionResponse response) {
        return write(out -> {
            uuid(out, response.ticketId());
            uuid(out, response.playerId());
            text(out, response.matchId().value());
            out.writeBoolean(response.accepted());
            out.writeByte(response.role().ordinal());
            text(out, response.reason());
        });
    }

    public static AdmissionResponse admissionResponse(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID ticketId = uuid(input);
            UUID playerId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            boolean accepted = input.readBoolean();
            int roleOrd = input.readUnsignedByte();
            if (roleOrd >= ParticipantRoleWire.values().length) throw new IOException("Invalid participant role");
            String reason = text(input);
            return new AdmissionResponse(ticketId, playerId, matchId, accepted, ParticipantRoleWire.values()[roleOrd], reason);
        }, "admission response");
    }

    public static byte[] participantStateChange(ParticipantStateChange change) {
        return write(out -> {
            text(out, change.matchId().value());
            uuid(out, change.playerId());
            out.writeByte(change.role().ordinal());
            out.writeByte(change.state().ordinal());
        });
    }

    public static ParticipantStateChange participantStateChange(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            MatchId matchId = MatchId.of(text(input));
            UUID playerId = uuid(input);
            int roleOrd = input.readUnsignedByte();
            int stateOrd = input.readUnsignedByte();
            if (roleOrd >= ParticipantRoleWire.values().length) throw new IOException("Invalid participant role");
            if (stateOrd >= ParticipantStateWire.values().length) throw new IOException("Invalid participant state");
            return new ParticipantStateChange(matchId, playerId,
                    ParticipantRoleWire.values()[roleOrd], ParticipantStateWire.values()[stateOrd]);
        }, "participant state change");
    }

    public static byte[] matchFinish(MatchFinish finish) {
        return write(out -> {
            text(out, finish.instanceId().value());
            uuid(out, finish.sessionId());
            text(out, finish.matchId().value());
            out.writeLong(finish.revision());
            out.writeByte(finish.outcome().ordinal());
            out.writeLong(finish.durationMillis());
            out.writeShort(finish.winnerIds().size());
            for (UUID id : finish.winnerIds()) {
                uuid(out, id);
            }
            longText(out, finish.metadataString() != null ? finish.metadataString() : "");
        });
    }

    public static MatchFinish matchFinish(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            long revision = input.readLong();
            int outcomeOrd = input.readUnsignedByte();
            if (outcomeOrd >= MatchResultOutcomeWire.values().length) throw new IOException("Invalid outcome");
            long durationMillis = input.readLong();
            int winnerCount = input.readUnsignedShort();
            if (winnerCount > 1000) throw new IOException("Excessive winner count");
            List<UUID> winners = new ArrayList<>(winnerCount);
            for (int i = 0; i < winnerCount; i++) {
                winners.add(uuid(input));
            }
            String metadata = longText(input);
            return new MatchFinish(instanceId, sessionId, matchId, revision,
                    MatchResultOutcomeWire.values()[outcomeOrd], durationMillis, winners, metadata);
        }, "match finish");
    }

    public static byte[] matchAbort(MatchAbort abort) {
        return write(out -> {
            text(out, abort.instanceId().value());
            uuid(out, abort.sessionId());
            text(out, abort.matchId().value());
            out.writeLong(abort.revision());
            text(out, abort.reason());
        });
    }

    public static MatchAbort matchAbort(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            long revision = input.readLong();
            String reason = text(input);
            return new MatchAbort(instanceId, sessionId, matchId, revision, reason);
        }, "match abort");
    }

    public static byte[] instanceReady(InstanceReady ready) {
        return write(out -> {
            text(out, ready.instanceId().value());
            uuid(out, ready.sessionId());
            text(out, ready.matchId().value());
        });
    }

    public static InstanceReady instanceReady(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            ServerId instanceId = ServerId.of(text(input));
            UUID sessionId = uuid(input);
            MatchId matchId = MatchId.of(text(input));
            return new InstanceReady(instanceId, sessionId, matchId);
        }, "instance ready");
    }

    public static byte[] playerReturn(PlayerReturn ret) {
        return write(out -> {
            uuid(out, ret.playerId());
            out.writeByte(ret.reason().ordinal());
            text(out, ret.message() != null ? ret.message() : "");
        });
    }

    public static PlayerReturn playerReturn(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID playerId = uuid(input);
            int reasonOrd = input.readUnsignedByte();
            if (reasonOrd >= ReturnReasonWire.values().length) throw new IOException("Invalid return reason");
            String message = text(input);
            return new PlayerReturn(playerId, ReturnReasonWire.values()[reasonOrd], message);
        }, "player return");
    }

    public static byte[] partyCreate(UUID leaderId) {
        return write(out -> uuid(out, leaderId));
    }

    public static PartyCreate partyCreate(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyCreate(uuid(input)), "party create");
    }

    public static byte[] partyInvite(PartyInvitePayload invite) {
        return write(out -> {
            uuid(out, invite.actorId());
            uuid(out, invite.targetId());
            text(out, invite.targetName() != null ? invite.targetName() : "");
        });
    }

    public static PartyInvitePayload partyInvite(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyInvitePayload(uuid(input), uuid(input), text(input)), "party invite");
    }

    public static byte[] partyAccept(PartyAcceptPayload accept) {
        return write(out -> {
            uuid(out, accept.playerId());
            out.writeBoolean(accept.partyId().isPresent());
            if (accept.partyId().isPresent()) {
                uuid(out, accept.partyId().get().value());
            }
        });
    }

    public static PartyAcceptPayload partyAccept(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID playerId = uuid(input);
            boolean present = input.readBoolean();
            Optional<PartyId> partyId = present ? Optional.of(PartyId.of(uuid(input))) : Optional.empty();
            return new PartyAcceptPayload(playerId, partyId);
        }, "party accept");
    }

    public static byte[] partyDecline(PartyDeclinePayload decline) {
        return write(out -> {
            uuid(out, decline.playerId());
            out.writeBoolean(decline.partyId().isPresent());
            if (decline.partyId().isPresent()) {
                uuid(out, decline.partyId().get().value());
            }
        });
    }

    public static PartyDeclinePayload partyDecline(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID playerId = uuid(input);
            boolean present = input.readBoolean();
            Optional<PartyId> partyId = present ? Optional.of(PartyId.of(uuid(input))) : Optional.empty();
            return new PartyDeclinePayload(playerId, partyId);
        }, "party decline");
    }

    public static byte[] partyLeave(UUID playerId) {
        return write(out -> uuid(out, playerId));
    }

    public static PartyLeavePayload partyLeave(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyLeavePayload(uuid(input)), "party leave");
    }

    public static byte[] partyKick(PartyKickPayload kick) {
        return write(out -> {
            uuid(out, kick.actorId());
            uuid(out, kick.targetId());
        });
    }

    public static PartyKickPayload partyKick(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyKickPayload(uuid(input), uuid(input)), "party kick");
    }

    public static byte[] partyLeaderChange(PartyLeaderChangePayload change) {
        return write(out -> {
            uuid(out, change.actorId());
            uuid(out, change.newLeaderId());
        });
    }

    public static PartyLeaderChangePayload partyLeaderChange(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyLeaderChangePayload(uuid(input), uuid(input)), "party leader change");
    }

    public static byte[] partyDisband(PartyDisbandPayload disband) {
        return write(out -> {
            uuid(out, disband.actorId());
            uuid(out, disband.partyId().value());
        });
    }

    public static PartyDisbandPayload partyDisband(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> new PartyDisbandPayload(uuid(input), PartyId.of(uuid(input))), "party disband");
    }

    public static byte[] partySync(PartySyncPayload sync) {
        return write(out -> {
            uuid(out, sync.partyId().value());
            uuid(out, sync.leaderId());
            out.writeShort(sync.members().size());
            for (UUID id : sync.members()) {
                uuid(out, id);
            }
            out.writeByte(sync.state().ordinal());
            out.writeLong(sync.revision());
        });
    }

    public static PartySyncPayload partySync(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            PartyId partyId = PartyId.of(uuid(input));
            UUID leaderId = uuid(input);
            int count = input.readUnsignedShort();
            if (count > 64) throw new IOException("Too many members in party sync");
            List<UUID> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(uuid(input));
            }
            int stateOrd = input.readUnsignedByte();
            if (stateOrd >= PartyStateWire.values().length) throw new IOException("Invalid party state wire");
            long revision = input.readLong();
            return new PartySyncPayload(partyId, leaderId, members, PartyStateWire.values()[stateOrd], revision);
        }, "party sync");
    }

    public static byte[] partyResponse(PartyResponsePayload resp) {
        return write(out -> {
            uuid(out, resp.playerId());
            out.writeBoolean(resp.success());
            text(out, resp.message() != null ? resp.message() : "");
            out.writeBoolean(resp.partyId().isPresent());
            if (resp.partyId().isPresent()) {
                uuid(out, resp.partyId().get().value());
            }
        });
    }

    public static PartyResponsePayload partyResponse(byte[] payload) throws ProtocolValidationException {
        return read(payload, input -> {
            UUID playerId = uuid(input);
            boolean success = input.readBoolean();
            String message = text(input);
            boolean present = input.readBoolean();
            Optional<PartyId> partyId = present ? Optional.of(PartyId.of(uuid(input))) : Optional.empty();
            return new PartyResponsePayload(playerId, success, message, partyId);
        }, "party response");
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

    private static void longText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Metadata text is too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String longText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_METADATA_BYTES) throw new IOException("Metadata text is too long");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated metadata text");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.PartyEvent;
import com.bigbangcraft.hub.api.PartyException;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyMember;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.common.InMemoryPartyService;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.PartyEventBus;
import com.bigbangcraft.hub.common.PartySettings;
import com.bigbangcraft.hub.common.ProtocolEnvelope;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class PaperPartyService implements PartyService {
    private final VelocityBridge bridge;
    private final InMemoryPartyService localFallback;
    private final PartyEventBus eventBus;

    PaperPartyService(VelocityBridge bridge, PartySettings settings, PartyEventBus eventBus) {
        this.bridge = bridge;
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.localFallback = new InMemoryPartyService(settings, eventBus);
    }

    @Override
    public PartySnapshot createParty(UUID leaderId) {
        if (bridge == null) return localFallback.createParty(leaderId);
        try {
            byte[] payload = MessagePayloads.partyCreate(leaderId);
            ProtocolEnvelope envelope = bridge.request(leaderId, MessageType.PARTY_CREATE, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY, resp.message());
            }
            PartyId partyId = resp.partyId().orElseGet(PartyId::random);
            return localFallback.party(partyId).orElseGet(() ->
                    new PartySnapshot(partyId, leaderId,
                            Map.of(leaderId, new PartyMember(leaderId, PartyRole.LEADER, Instant.now())),
                            Map.of(), PartyState.IDLE, Instant.now(), 1L));
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.createParty(leaderId);
        }
    }

    @Override
    public PartyInvite invitePlayer(UUID actorId, UUID targetId) {
        if (bridge == null) return localFallback.invitePlayer(actorId, targetId);
        try {
            byte[] payload = MessagePayloads.partyInvite(new MessagePayloads.PartyInvitePayload(actorId, targetId, ""));
            ProtocolEnvelope envelope = bridge.request(actorId, MessageType.PARTY_INVITE, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.RATE_LIMITED, resp.message());
            }
            PartyId partyId = resp.partyId().orElseGet(PartyId::random);
            return new PartyInvite(partyId, actorId, targetId, Instant.now(), Instant.now().plus(inviteTtl()));
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.invitePlayer(actorId, targetId);
        }
    }

    @Override
    public PartySnapshot acceptInvite(UUID playerId, PartyId partyId) {
        if (bridge == null) return localFallback.acceptInvite(playerId, partyId);
        try {
            byte[] payload = MessagePayloads.partyAccept(new MessagePayloads.PartyAcceptPayload(playerId, Optional.ofNullable(partyId)));
            ProtocolEnvelope envelope = bridge.request(playerId, MessageType.PARTY_ACCEPT, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.INVITE_NOT_FOUND, resp.message());
            }
            PartyId pid = resp.partyId().orElse(partyId != null ? partyId : PartyId.random());
            return localFallback.party(pid).orElseGet(() ->
                    new PartySnapshot(pid, playerId,
                            Map.of(playerId, new PartyMember(playerId, PartyRole.MEMBER, Instant.now())),
                            Map.of(), PartyState.IDLE, Instant.now(), 1L));
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.acceptInvite(playerId, partyId);
        }
    }

    @Override
    public void declineInvite(UUID playerId, PartyId partyId) {
        if (bridge == null) {
            localFallback.declineInvite(playerId, partyId);
            return;
        }
        try {
            byte[] payload = MessagePayloads.partyDecline(new MessagePayloads.PartyDeclinePayload(playerId, Optional.ofNullable(partyId)));
            bridge.request(playerId, MessageType.PARTY_DECLINE, payload);
        } catch (Exception e) {
            localFallback.declineInvite(playerId, partyId);
        }
    }

    @Override
    public PartySnapshot leaveParty(UUID playerId) {
        if (bridge == null) return localFallback.leaveParty(playerId);
        try {
            byte[] payload = MessagePayloads.partyLeave(playerId);
            ProtocolEnvelope envelope = bridge.request(playerId, MessageType.PARTY_LEAVE, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, resp.message());
            }
            PartyId pid = resp.partyId().orElseGet(PartyId::random);
            return new PartySnapshot(pid, playerId, Map.of(), Map.of(), PartyState.DISBANDING, Instant.now(), 1L);
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.leaveParty(playerId);
        }
    }

    @Override
    public PartySnapshot kickPlayer(UUID actorId, UUID targetId) {
        if (bridge == null) return localFallback.kickPlayer(actorId, targetId);
        try {
            byte[] payload = MessagePayloads.partyKick(new MessagePayloads.PartyKickPayload(actorId, targetId));
            ProtocolEnvelope envelope = bridge.request(actorId, MessageType.PARTY_KICK, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER, resp.message());
            }
            PartyId pid = resp.partyId().orElseGet(PartyId::random);
            return new PartySnapshot(pid, actorId, Map.of(actorId, new PartyMember(actorId, PartyRole.LEADER, Instant.now())), Map.of(), PartyState.IDLE, Instant.now(), 1L);
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.kickPlayer(actorId, targetId);
        }
    }

    @Override
    public PartySnapshot transferLeadership(UUID actorId, UUID newLeaderId) {
        if (bridge == null) return localFallback.transferLeadership(actorId, newLeaderId);
        try {
            byte[] payload = MessagePayloads.partyLeaderChange(new MessagePayloads.PartyLeaderChangePayload(actorId, newLeaderId));
            ProtocolEnvelope envelope = bridge.request(actorId, MessageType.PARTY_LEADER_CHANGE, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER, resp.message());
            }
            PartyId pid = resp.partyId().orElseGet(PartyId::random);
            return new PartySnapshot(pid, newLeaderId, Map.of(newLeaderId, new PartyMember(newLeaderId, PartyRole.LEADER, Instant.now())), Map.of(), PartyState.IDLE, Instant.now(), 1L);
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.transferLeadership(actorId, newLeaderId);
        }
    }

    @Override
    public PartySnapshot disbandParty(UUID actorId, PartyId partyId) {
        if (bridge == null) return localFallback.disbandParty(actorId, partyId);
        try {
            byte[] payload = MessagePayloads.partyDisband(new MessagePayloads.PartyDisbandPayload(actorId, partyId));
            ProtocolEnvelope envelope = bridge.request(actorId, MessageType.PARTY_DISBAND, payload)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(envelope.payload());
            if (!resp.success()) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER, resp.message());
            }
            return new PartySnapshot(partyId, actorId, Map.of(), Map.of(), PartyState.DISBANDING, Instant.now(), 1L);
        } catch (PartyException e) {
            throw e;
        } catch (Exception e) {
            return localFallback.disbandParty(actorId, partyId);
        }
    }

    @Override
    public PartySnapshot transitionState(PartyId partyId, PartyState newState) {
        return localFallback.transitionState(partyId, newState);
    }

    @Override public Optional<PartySnapshot> partyOf(UUID playerId) { return localFallback.partyOf(playerId); }
    @Override public Optional<PartySnapshot> party(PartyId partyId) { return localFallback.party(partyId); }
    @Override public Set<UUID> members(PartyId partyId) { return localFallback.members(partyId); }
    @Override public Collection<PartySnapshot> activeParties() { return localFallback.activeParties(); }
    @Override public int maxPartySize() { return localFallback.maxPartySize(); }
    @Override public Duration inviteTtl() { return localFallback.inviteTtl(); }
    @Override public void handlePlayerDisconnect(UUID playerId) { localFallback.handlePlayerDisconnect(playerId); }
    @Override public void handlePlayerReconnect(UUID playerId) { localFallback.handlePlayerReconnect(playerId); }

    @Override public void addListener(Consumer<PartyEvent> listener) { eventBus.add(listener); }
    @Override public void removeListener(Consumer<PartyEvent> listener) { eventBus.remove(listener); }

    PartyEventBus eventBus() { return eventBus; }
    InMemoryPartyService localFallback() { return localFallback; }
    void clear() { localFallback.clear(); }
}

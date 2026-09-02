package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.PartyEvent;
import com.bigbangcraft.hub.api.PartyException;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyLeaderChangedEvent;
import com.bigbangcraft.hub.api.PartyMember;
import com.bigbangcraft.hub.api.PartyMemberJoinedEvent;
import com.bigbangcraft.hub.api.PartyMemberLeftEvent;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.PartyStateChangedEvent;
import com.bigbangcraft.hub.api.PartyCreatedEvent;
import com.bigbangcraft.hub.api.PartyDisbandedEvent;
import com.bigbangcraft.hub.api.PartyInviteCreatedEvent;
import com.bigbangcraft.hub.api.PartyInviteExpiredEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Authoritative thread-safe in-memory implementation of {@link PartyService}.
 * Strict network invariants:
 * - 1 player <= 1 party at any given time.
 * - Party has exactly one leader who belongs to members.
 * - Empty parties are immediately disbanded.
 * - Players cannot invite themselves.
 * - Expired invites cannot be accepted.
 * - Party size strictly respects configured maximum.
 * - Single-use invites; accepting an invite invalidates all other pending invites for that player.
 * - Reentrant lock prevents concurrent membership corruptions.
 */
public final class InMemoryPartyService implements PartyService {

    private static final class PartyInternalState {
        private final PartyId partyId;
        private UUID leader;
        private final Map<UUID, PartyMember> members = new LinkedHashMap<>();
        private final Map<UUID, PartyInvite> invitedPlayers = new LinkedHashMap<>();
        private PartyState state;
        private final Instant createdAt;
        private long revision;

        private PartyInternalState(PartyId partyId, UUID leader, PartyState state, Instant createdAt, long revision) {
            this.partyId = Objects.requireNonNull(partyId, "partyId");
            this.leader = Objects.requireNonNull(leader, "leader");
            this.state = Objects.requireNonNull(state, "state");
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
            this.revision = revision;
        }

        private PartySnapshot snapshot() {
            return new PartySnapshot(partyId, leader, members, invitedPlayers, state, createdAt, revision);
        }
    }

    private record DisconnectedInfo(PartyId partyId, Instant disconnectedAt) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final PartySettings settings;
    private final PartyEventBus eventBus;
    private volatile Clock clock;

    private final Map<PartyId, PartyInternalState> parties = new HashMap<>();
    private final Map<UUID, PartyId> playerPartyMap = new HashMap<>();
    private final Map<UUID, Map<PartyId, PartyInvite>> pendingInvitesByTarget = new HashMap<>();
    private final Map<UUID, Instant> lastInviteTimeByActor = new HashMap<>();
    private final Map<UUID, DisconnectedInfo> disconnectedMembers = new HashMap<>();

    public InMemoryPartyService(PartySettings settings, PartyEventBus eventBus, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InMemoryPartyService(PartySettings settings, PartyEventBus eventBus) {
        this(settings, eventBus, Clock.systemUTC());
    }

    public void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private Instant now() {
        return clock.instant();
    }

    @Override
    public PartySnapshot createParty(UUID leaderId) {
        Objects.requireNonNull(leaderId, "leaderId");
        PartySnapshot createdSnapshot;
        Instant timestamp = now();

        lock.lock();
        try {
            if (playerPartyMap.containsKey(leaderId)) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY,
                        "Player " + leaderId + " is already in a party");
            }

            PartyId partyId = PartyId.random();
            PartyInternalState state = new PartyInternalState(partyId, leaderId, PartyState.IDLE, timestamp, 1L);
            PartyMember leaderMember = new PartyMember(leaderId, PartyRole.LEADER, timestamp);
            state.members.put(leaderId, leaderMember);

            parties.put(partyId, state);
            playerPartyMap.put(leaderId, partyId);
            disconnectedMembers.remove(leaderId);

            createdSnapshot = state.snapshot();
        } finally {
            lock.unlock();
        }

        eventBus.publish(new PartyCreatedEvent(createdSnapshot, timestamp));
        return createdSnapshot;
    }

    @Override
    public PartySnapshot disbandParty(UUID actorId, PartyId partyId) {
        Objects.requireNonNull(partyId, "partyId");
        PartySnapshot disbandedSnapshot;
        Instant timestamp = now();
        List<PartyEvent> eventsToPublish = new ArrayList<>(2);

        lock.lock();
        try {
            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (actorId != null && !party.leader.equals(actorId)) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER,
                        "Only party leader can disband party");
            }

            if (party.state.isLocked()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_MUTATION_LOCKED,
                        "Cannot disband party while in state " + party.state);
            }

            party.state = PartyState.DISBANDING;
            party.revision++;

            // Clean members
            for (UUID memberId : party.members.keySet()) {
                playerPartyMap.remove(memberId, partyId);
                disconnectedMembers.remove(memberId);
            }

            // Clean pending invites
            for (PartyInvite invite : party.invitedPlayers.values()) {
                Map<PartyId, PartyInvite> targetPending = pendingInvitesByTarget.get(invite.target());
                if (targetPending != null) {
                    targetPending.remove(partyId);
                    if (targetPending.isEmpty()) {
                        pendingInvitesByTarget.remove(invite.target());
                    }
                }
            }

            parties.remove(partyId);
            disbandedSnapshot = party.snapshot();
            eventsToPublish.add(new PartyDisbandedEvent(
                    disbandedSnapshot, "Disbanded by " + (actorId != null ? actorId : "system"), timestamp));
        } finally {
            lock.unlock();
        }

        for (PartyEvent event : eventsToPublish) {
            eventBus.publish(event);
        }
        return disbandedSnapshot;
    }

    @Override
    public PartyInvite invitePlayer(UUID actorId, UUID targetId) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");

        if (actorId.equals(targetId)) {
            throw new PartyException(PartyException.ErrorCode.CANNOT_INVITE_SELF, "Cannot invite self");
        }

        Instant timestamp = now();
        PartyInvite invite;

        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(actorId);
            if (partyId == null) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, "Actor is not in a party");
            }

            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (!party.leader.equals(actorId)) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER,
                        "Only party leader can invite players");
            }

            if (party.state.isLocked()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_MUTATION_LOCKED,
                        "Cannot invite players while party is " + party.state);
            }

            if (playerPartyMap.containsKey(targetId)) {
                throw new PartyException(PartyException.ErrorCode.TARGET_ALREADY_IN_PARTY,
                        "Target player is already in a party");
            }

            if (party.members.size() >= settings.maxSize()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_FULL,
                        "Party has reached maximum size of " + settings.maxSize());
            }

            Instant lastInvite = lastInviteTimeByActor.get(actorId);
            if (lastInvite != null && Duration.between(lastInvite, timestamp).compareTo(settings.inviteCooldown()) < 0) {
                throw new PartyException(PartyException.ErrorCode.RATE_LIMITED,
                        "Please wait before sending another party invitation");
            }

            PartyInvite existing = party.invitedPlayers.get(targetId);
            if (existing != null && !existing.isExpired(timestamp)) {
                throw new PartyException(PartyException.ErrorCode.INVITE_ALREADY_PENDING,
                        "An invite is already pending for this player");
            }

            invite = new PartyInvite(partyId, actorId, targetId, timestamp, timestamp.plus(settings.inviteTtl()));
            party.invitedPlayers.put(targetId, invite);
            pendingInvitesByTarget.computeIfAbsent(targetId, k -> new LinkedHashMap<>()).put(partyId, invite);
            lastInviteTimeByActor.put(actorId, timestamp);
        } finally {
            lock.unlock();
        }

        eventBus.publish(new PartyInviteCreatedEvent(invite, timestamp));
        return invite;
    }

    @Override
    public PartySnapshot acceptInvite(UUID playerId, PartyId partyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");

        Instant timestamp = now();
        PartySnapshot updatedSnapshot;
        List<PartyEvent> eventsToPublish = new ArrayList<>(2);

        lock.lock();
        try {
            if (playerPartyMap.containsKey(playerId)) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY,
                        "Player is already in a party");
            }

            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (party.state.isLocked()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_MUTATION_LOCKED,
                        "Party is currently locked: " + party.state);
            }

            if (party.members.size() >= settings.maxSize()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_FULL, "Party is full");
            }

            PartyInvite invite = party.invitedPlayers.get(playerId);
            if (invite == null) {
                throw new PartyException(PartyException.ErrorCode.INVITE_NOT_FOUND, "No invite found for player");
            }

            if (invite.isExpired(timestamp)) {
                party.invitedPlayers.remove(playerId);
                Map<PartyId, PartyInvite> targetPending = pendingInvitesByTarget.get(playerId);
                if (targetPending != null) {
                    targetPending.remove(partyId);
                    if (targetPending.isEmpty()) pendingInvitesByTarget.remove(playerId);
                }
                eventsToPublish.add(new PartyInviteExpiredEvent(invite, timestamp));
                throw new PartyException(PartyException.ErrorCode.INVITE_EXPIRED, "Party invite has expired");
            }

            // Consume invite
            party.invitedPlayers.remove(playerId);

            // Invalidate all other pending invites for this player from other parties
            Map<PartyId, PartyInvite> allInvites = pendingInvitesByTarget.remove(playerId);
            if (allInvites != null) {
                for (Map.Entry<PartyId, PartyInvite> entry : allInvites.entrySet()) {
                    if (!entry.getKey().equals(partyId)) {
                        PartyInternalState otherParty = parties.get(entry.getKey());
                        if (otherParty != null) {
                            otherParty.invitedPlayers.remove(playerId);
                        }
                    }
                }
            }

            PartyMember newMember = new PartyMember(playerId, PartyRole.MEMBER, timestamp);
            party.members.put(playerId, newMember);
            playerPartyMap.put(playerId, partyId);
            disconnectedMembers.remove(playerId);
            party.revision++;

            updatedSnapshot = party.snapshot();
            eventsToPublish.add(new PartyMemberJoinedEvent(
                    partyId, playerId, PartyRole.MEMBER, party.revision, timestamp));
        } finally {
            lock.unlock();
        }

        for (PartyEvent event : eventsToPublish) {
            eventBus.publish(event);
        }
        return updatedSnapshot;
    }

    @Override
    public void declineInvite(UUID playerId, PartyId partyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(partyId, "partyId");

        Instant timestamp = now();
        PartyInvite expiredToPublish = null;

        lock.lock();
        try {
            PartyInternalState party = parties.get(partyId);
            if (party != null) {
                PartyInvite invite = party.invitedPlayers.remove(playerId);
                if (invite != null && invite.isExpired(timestamp)) {
                    expiredToPublish = invite;
                }
            }

            Map<PartyId, PartyInvite> targetPending = pendingInvitesByTarget.get(playerId);
            if (targetPending != null) {
                targetPending.remove(partyId);
                if (targetPending.isEmpty()) {
                    pendingInvitesByTarget.remove(playerId);
                }
            }
        } finally {
            lock.unlock();
        }

        if (expiredToPublish != null) {
            eventBus.publish(new PartyInviteExpiredEvent(expiredToPublish, timestamp));
        }
    }

    @Override
    public PartySnapshot leaveParty(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");

        Instant timestamp = now();
        PartySnapshot resultSnapshot;
        List<PartyEvent> eventsToPublish = new ArrayList<>(2);

        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(playerId);
            if (partyId == null) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, "Player is not in a party");
            }

            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (party.state.isLocked()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_MUTATION_LOCKED,
                        "Cannot leave party while " + party.state);
            }

            if (party.members.size() <= 1) {
                // Last member leaving disbands party
                return disbandParty(null, partyId);
            }

            if (party.leader.equals(playerId)) {
                // Leader leaving: transfer leadership to the earliest joined member
                party.members.remove(playerId);
                playerPartyMap.remove(playerId, partyId);
                disconnectedMembers.remove(playerId);

                UUID nextLeader = party.members.keySet().iterator().next();
                PartyMember oldNext = party.members.get(nextLeader);
                PartyMember newLeaderMember = new PartyMember(nextLeader, PartyRole.LEADER, oldNext.joinedAt());
                party.members.put(nextLeader, newLeaderMember);
                party.leader = nextLeader;
                party.revision++;

                resultSnapshot = party.snapshot();
                eventsToPublish.add(new PartyMemberLeftEvent(partyId, playerId, "LEAVE", party.revision, timestamp));
                eventsToPublish.add(new PartyLeaderChangedEvent(partyId, playerId, nextLeader, party.revision, timestamp));
            } else {
                // Regular member leaving
                party.members.remove(playerId);
                playerPartyMap.remove(playerId, partyId);
                disconnectedMembers.remove(playerId);
                party.revision++;

                resultSnapshot = party.snapshot();
                eventsToPublish.add(new PartyMemberLeftEvent(partyId, playerId, "LEAVE", party.revision, timestamp));
            }
        } finally {
            lock.unlock();
        }

        for (PartyEvent event : eventsToPublish) {
            eventBus.publish(event);
        }
        return resultSnapshot;
    }

    @Override
    public PartySnapshot kickPlayer(UUID actorId, UUID targetId) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(targetId, "targetId");

        Instant timestamp = now();
        PartySnapshot resultSnapshot;

        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(actorId);
            if (partyId == null) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, "Actor is not in a party");
            }

            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (!party.leader.equals(actorId)) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER, "Only leader can kick members");
            }

            if (actorId.equals(targetId)) {
                throw new PartyException(PartyException.ErrorCode.CANNOT_KICK_LEADER, "Leader cannot kick themselves");
            }

            if (!party.members.containsKey(targetId)) {
                throw new PartyException(PartyException.ErrorCode.TARGET_NOT_IN_PARTY,
                        "Target player is not in this party");
            }

            if (party.state.isLocked()) {
                throw new PartyException(PartyException.ErrorCode.PARTY_MUTATION_LOCKED,
                        "Cannot kick players while party is " + party.state);
            }

            party.members.remove(targetId);
            playerPartyMap.remove(targetId, partyId);
            disconnectedMembers.remove(targetId);
            party.revision++;

            resultSnapshot = party.snapshot();
        } finally {
            lock.unlock();
        }

        eventBus.publish(new PartyMemberLeftEvent(resultSnapshot.partyId(), targetId, "KICK", resultSnapshot.revision(), timestamp));
        return resultSnapshot;
    }

    @Override
    public PartySnapshot transferLeadership(UUID actorId, UUID newLeaderId) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(newLeaderId, "newLeaderId");

        if (actorId.equals(newLeaderId)) {
            throw new PartyException(PartyException.ErrorCode.CANNOT_TRANSFER_TO_SELF,
                    "Cannot transfer leadership to self");
        }

        Instant timestamp = now();
        PartySnapshot resultSnapshot;

        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(actorId);
            if (partyId == null) {
                throw new PartyException(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, "Actor is not in a party");
            }

            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            if (!party.leader.equals(actorId)) {
                throw new PartyException(PartyException.ErrorCode.NOT_PARTY_LEADER,
                        "Only current leader can transfer leadership");
            }

            if (!party.members.containsKey(newLeaderId)) {
                throw new PartyException(PartyException.ErrorCode.TARGET_NOT_IN_PARTY,
                        "New leader is not in this party");
            }

            PartyMember oldLeader = party.members.get(actorId);
            party.members.put(actorId, new PartyMember(actorId, PartyRole.MEMBER, oldLeader.joinedAt()));

            PartyMember newLeader = party.members.get(newLeaderId);
            party.members.put(newLeaderId, new PartyMember(newLeaderId, PartyRole.LEADER, newLeader.joinedAt()));

            party.leader = newLeaderId;
            party.revision++;

            resultSnapshot = party.snapshot();
        } finally {
            lock.unlock();
        }

        eventBus.publish(new PartyLeaderChangedEvent(resultSnapshot.partyId(), actorId, newLeaderId, resultSnapshot.revision(), timestamp));
        return resultSnapshot;
    }

    @Override
    public PartySnapshot transitionState(PartyId partyId, PartyState newState) {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(newState, "newState");

        Instant timestamp = now();
        PartySnapshot resultSnapshot;
        PartyState oldState;

        lock.lock();
        try {
            PartyInternalState party = parties.get(partyId);
            if (party == null) {
                throw new PartyException(PartyException.ErrorCode.PARTY_NOT_FOUND, "Party not found: " + partyId);
            }

            oldState = party.state;
            if (oldState == newState) {
                return party.snapshot();
            }

            party.state = newState;
            party.revision++;
            resultSnapshot = party.snapshot();
        } finally {
            lock.unlock();
        }

        eventBus.publish(new PartyStateChangedEvent(partyId, oldState, newState, resultSnapshot.revision(), timestamp));
        return resultSnapshot;
    }

    @Override
    public Optional<PartySnapshot> partyOf(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(playerId);
            if (partyId == null) return Optional.empty();
            PartyInternalState party = parties.get(partyId);
            return party != null ? Optional.of(party.snapshot()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PartySnapshot> party(PartyId partyId) {
        Objects.requireNonNull(partyId, "partyId");
        lock.lock();
        try {
            PartyInternalState party = parties.get(partyId);
            return party != null ? Optional.of(party.snapshot()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Set<UUID> members(PartyId partyId) {
        Objects.requireNonNull(partyId, "partyId");
        lock.lock();
        try {
            PartyInternalState party = parties.get(partyId);
            return party != null ? Set.copyOf(party.members.keySet()) : Set.of();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Collection<PartySnapshot> activeParties() {
        lock.lock();
        try {
            List<PartySnapshot> list = new ArrayList<>(parties.size());
            for (PartyInternalState party : parties.values()) {
                list.add(party.snapshot());
            }
            return Collections.unmodifiableCollection(list);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handlePlayerDisconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            PartyId partyId = playerPartyMap.get(playerId);
            if (partyId != null) {
                disconnectedMembers.put(playerId, new DisconnectedInfo(partyId, now()));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void handlePlayerReconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            disconnectedMembers.remove(playerId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Periodic maintenance sweep:
     * - Sweeps expired invitations.
     * - Sweeps disconnected party members and leaders whose grace period has expired.
     */
    public void sweep() {
        Instant timestamp = now();
        List<PartyEvent> eventsToPublish = new ArrayList<>();

        lock.lock();
        try {
            // 1. Sweep expired invites
            for (PartyInternalState party : parties.values()) {
                Iterator<Map.Entry<UUID, PartyInvite>> it = party.invitedPlayers.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, PartyInvite> entry = it.next();
                    if (entry.getValue().isExpired(timestamp)) {
                        it.remove();
                        Map<PartyId, PartyInvite> targetPending = pendingInvitesByTarget.get(entry.getKey());
                        if (targetPending != null) {
                            targetPending.remove(party.partyId);
                            if (targetPending.isEmpty()) pendingInvitesByTarget.remove(entry.getKey());
                        }
                        eventsToPublish.add(new PartyInviteExpiredEvent(entry.getValue(), timestamp));
                    }
                }
            }

            // 2. Sweep disconnected members whose grace period expired
            Iterator<Map.Entry<UUID, DisconnectedInfo>> discIt = disconnectedMembers.entrySet().iterator();
            while (discIt.hasNext()) {
                Map.Entry<UUID, DisconnectedInfo> entry = discIt.next();
                UUID playerId = entry.getKey();
                DisconnectedInfo info = entry.getValue();

                if (Duration.between(info.disconnectedAt(), timestamp).compareTo(settings.leaderDisconnectGrace()) >= 0) {
                    discIt.remove();
                    PartyInternalState party = parties.get(info.partyId());
                    if (party != null && party.members.containsKey(playerId)) {
                        if (party.members.size() <= 1) {
                            // Lone player expired -> disband party
                            party.state = PartyState.DISBANDING;
                            party.revision++;
                            playerPartyMap.remove(playerId, info.partyId());
                            parties.remove(info.partyId());
                            eventsToPublish.add(new PartyDisbandedEvent(
                                    party.snapshot(), "Leader disconnected and grace period expired", timestamp));
                        } else if (party.leader.equals(playerId)) {
                            // Leader expired -> transfer leadership to next eligible member
                            party.members.remove(playerId);
                            playerPartyMap.remove(playerId, info.partyId());

                            UUID nextLeader = party.members.keySet().iterator().next();
                            PartyMember oldNext = party.members.get(nextLeader);
                            party.members.put(nextLeader, new PartyMember(nextLeader, PartyRole.LEADER, oldNext.joinedAt()));
                            party.leader = nextLeader;
                            party.revision++;

                            eventsToPublish.add(new PartyMemberLeftEvent(
                                    info.partyId(), playerId, "DISCONNECT_TIMEOUT", party.revision, timestamp));
                            eventsToPublish.add(new PartyLeaderChangedEvent(
                                    info.partyId(), playerId, nextLeader, party.revision, timestamp));
                        } else {
                            // Regular member expired -> remove
                            party.members.remove(playerId);
                            playerPartyMap.remove(playerId, info.partyId());
                            party.revision++;

                            eventsToPublish.add(new PartyMemberLeftEvent(
                                    info.partyId(), playerId, "DISCONNECT_TIMEOUT", party.revision, timestamp));
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        for (PartyEvent event : eventsToPublish) {
            eventBus.publish(event);
        }
    }

    public void clear() {
        lock.lock();
        try {
            parties.clear();
            playerPartyMap.clear();
            pendingInvitesByTarget.clear();
            lastInviteTimeByActor.clear();
            disconnectedMembers.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int maxPartySize() {
        return settings.maxSize();
    }

    @Override
    public Duration inviteTtl() {
        return settings.inviteTtl();
    }

    @Override
    public void addListener(Consumer<PartyEvent> listener) {
        eventBus.add(listener);
    }

    @Override
    public void removeListener(Consumer<PartyEvent> listener) {
        eventBus.remove(listener);
    }
}

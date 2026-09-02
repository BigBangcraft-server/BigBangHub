package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable snapshot representing party state at a specific revision. */
public record PartySnapshot(
        PartyId partyId,
        UUID leader,
        Map<UUID, PartyMember> members,
        Map<UUID, PartyInvite> invitedPlayers,
        PartyState state,
        Instant createdAt,
        long revision) {

    public PartySnapshot {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        invitedPlayers = Collections.unmodifiableMap(new LinkedHashMap<>(invitedPlayers));
        if (state != PartyState.DISBANDING && !members.containsKey(leader)) {
            throw new IllegalArgumentException("Leader must belong to party members unless disbanding");
        }
    }

    public int size() {
        return members.size();
    }

    public Set<UUID> memberIds() {
        return members.keySet();
    }

    public boolean isLeader(UUID playerId) {
        return leader.equals(playerId);
    }

    public boolean containsMember(UUID playerId) {
        return members.containsKey(playerId);
    }

    public Optional<PartyRole> roleOf(UUID playerId) {
        PartyMember member = members.get(playerId);
        return member != null ? Optional.of(member.role()) : Optional.empty();
    }

    public Optional<PartyInvite> inviteFor(UUID playerId) {
        return Optional.ofNullable(invitedPlayers.get(playerId));
    }
}

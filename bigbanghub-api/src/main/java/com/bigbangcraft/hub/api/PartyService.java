package com.bigbangcraft.hub.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Public service interface for party operations across the network.
 * All operations are thread-safe and enforce core domain invariants.
 */
public interface PartyService {
    PartySnapshot createParty(UUID leaderId);

    PartySnapshot disbandParty(UUID actorId, PartyId partyId);

    PartyInvite invitePlayer(UUID actorId, UUID targetId);

    PartySnapshot acceptInvite(UUID playerId, PartyId partyId);

    void declineInvite(UUID playerId, PartyId partyId);

    PartySnapshot leaveParty(UUID playerId);

    PartySnapshot kickPlayer(UUID actorId, UUID targetId);

    PartySnapshot transferLeadership(UUID actorId, UUID newLeaderId);

    PartySnapshot transitionState(PartyId partyId, PartyState newState);

    Optional<PartySnapshot> partyOf(UUID playerId);

    Optional<PartySnapshot> party(PartyId partyId);

    Set<UUID> members(PartyId partyId);

    Collection<PartySnapshot> activeParties();

    void handlePlayerDisconnect(UUID playerId);

    void handlePlayerReconnect(UUID playerId);

    int maxPartySize();

    Duration inviteTtl();

    void addListener(Consumer<PartyEvent> listener);

    void removeListener(Consumer<PartyEvent> listener);
}

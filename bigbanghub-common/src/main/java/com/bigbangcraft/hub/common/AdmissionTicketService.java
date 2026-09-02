package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.ServerId;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing transient, single-use admission tickets for match admission handshake.
 */
public final class AdmissionTicketService {
    private final Duration defaultTtl;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, AdmissionTicket> ticketsById = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeByPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> consumedTickets = ConcurrentHashMap.newKeySet();

    public AdmissionTicketService(Duration defaultTtl) {
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl");
        if (defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("defaultTtl must be positive");
        }
    }

    public synchronized AdmissionTicket issue(
            UUID playerId, MatchId matchId, ServerId instanceId, ParticipantRole role, Instant now) {
        return issue(playerId, matchId, instanceId, role, now, defaultTtl, Optional.empty());
    }

    public synchronized AdmissionTicket issue(
            UUID playerId, MatchId matchId, ServerId instanceId, ParticipantRole role, Instant now, Duration ttl) {
        return issue(playerId, matchId, instanceId, role, now, ttl, Optional.empty());
    }

    public synchronized AdmissionTicket issue(
            UUID playerId, MatchId matchId, ServerId instanceId, ParticipantRole role, Instant now, Duration ttl, Optional<PartyId> partyId) {
        return issue(playerId, matchId, instanceId, role, now, ttl, partyId, false);
    }

    public synchronized AdmissionTicket issue(
            UUID playerId, MatchId matchId, ServerId instanceId, ParticipantRole role, Instant now, Duration ttl, Optional<PartyId> partyId, boolean isReconnect) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(partyId, "partyId");

        UUID existingTicketId = activeByPlayer.remove(playerId);
        if (existingTicketId != null) {
            ticketsById.remove(existingTicketId);
        }

        UUID ticketId = UUID.randomUUID();
        byte[] tokenBytes = new byte[16];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);

        AdmissionTicket ticket = new AdmissionTicket(
                ticketId, playerId, matchId, instanceId, role, now, now.plus(ttl), token, partyId, isReconnect);

        ticketsById.put(ticketId, ticket);
        activeByPlayer.put(playerId, ticketId);
        return ticket;
    }

    public synchronized AdmissionTicket consume(
            UUID ticketId, UUID playerId, MatchId matchId, ServerId instanceId, String token, Instant now) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(now, "now");

        if (consumedTickets.contains(ticketId)) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket was already consumed (replay attack detected)");
        }

        AdmissionTicket ticket = ticketsById.get(ticketId);
        if (ticket == null) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket not found");
        }

        if (ticket.isExpired(now)) {
            ticketsById.remove(ticketId);
            activeByPlayer.remove(playerId);
            throw new MatchException(MatchException.ErrorCode.ADMISSION_EXPIRED, "Admission ticket has expired");
        }

        if (!ticket.playerId().equals(playerId)) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket player ID mismatch");
        }

        if (!ticket.matchId().equals(matchId)) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket match ID mismatch");
        }

        if (!ticket.instanceId().equals(instanceId)) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket instance ID mismatch");
        }

        if (!ticket.token().equals(token)) {
            throw new MatchException(MatchException.ErrorCode.UNAUTHORIZED, "Ticket token mismatch");
        }

        ticketsById.remove(ticketId);
        activeByPlayer.remove(playerId);
        consumedTickets.add(ticketId);
        return ticket;
    }

    public synchronized Optional<AdmissionTicket> findActive(UUID playerId) {
        UUID ticketId = activeByPlayer.get(playerId);
        if (ticketId == null) return Optional.empty();
        return Optional.ofNullable(ticketsById.get(ticketId));
    }

    public synchronized Optional<AdmissionTicket> find(UUID ticketId) {
        return Optional.ofNullable(ticketsById.get(ticketId));
    }

    public synchronized void invalidate(UUID ticketId) {
        AdmissionTicket ticket = ticketsById.remove(ticketId);
        if (ticket != null) {
            activeByPlayer.remove(ticket.playerId(), ticketId);
        }
    }

    public synchronized void invalidateForPlayer(UUID playerId) {
        UUID ticketId = activeByPlayer.remove(playerId);
        if (ticketId != null) {
            ticketsById.remove(ticketId);
        }
    }

    public synchronized void invalidateForMatch(MatchId matchId) {
        List<UUID> toRemove = new ArrayList<>();
        for (AdmissionTicket ticket : ticketsById.values()) {
            if (ticket.matchId().equals(matchId)) {
                toRemove.add(ticket.ticketId());
            }
        }
        for (UUID id : toRemove) {
            invalidate(id);
        }
    }

    public synchronized List<AdmissionTicket> sweepExpired(Instant now) {
        List<AdmissionTicket> expired = new ArrayList<>();
        for (AdmissionTicket ticket : ticketsById.values()) {
            if (ticket.isExpired(now)) {
                expired.add(ticket);
            }
        }
        for (AdmissionTicket ticket : expired) {
            invalidate(ticket.ticketId());
        }
        if (consumedTickets.size() > 5000) {
            consumedTickets.clear();
        }
        return expired;
    }

    public synchronized void clear() {
        ticketsById.clear();
        activeByPlayer.clear();
        consumedTickets.clear();
    }
}

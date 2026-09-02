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
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPartyServiceTest {

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock(Instant start, ZoneId zone) {
            this.current = start;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        public void advance(Duration duration) {
            current = current.plus(duration);
        }
    }

    private MutableClock clock;
    private PartyEventBus eventBus;
    private InMemoryPartyService partyService;
    private List<PartyEvent> eventsReceived;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-01T12:00:00Z"), ZoneId.of("UTC"));
        eventBus = new PartyEventBus();
        eventsReceived = Collections.synchronizedList(new ArrayList<>());
        eventBus.add(eventsReceived::add);
        PartySettings settings = new PartySettings(4, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ZERO);
        partyService = new InMemoryPartyService(settings, eventBus, clock);
    }

    @Test
    void testCreateParty() {
        UUID leader = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        assertNotNull(party.partyId());
        assertEquals(leader, party.leader());
        assertEquals(1, party.size());
        assertEquals(PartyState.IDLE, party.state());
        assertTrue(party.containsMember(leader));
        assertEquals(Optional.of(PartyRole.LEADER), party.roleOf(leader));

        Optional<PartySnapshot> byPlayer = partyService.partyOf(leader);
        assertTrue(byPlayer.isPresent());
        assertEquals(party.partyId(), byPlayer.get().partyId());
    }

    @Test
    void testDuplicateCreateFails() {
        UUID leader = UUID.randomUUID();
        partyService.createParty(leader);

        PartyException ex = assertThrows(PartyException.class, () -> partyService.createParty(leader));
        assertEquals(PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY, ex.errorCode());
    }

    @Test
    void testInvitePlayerAndCannotInviteSelf() {
        UUID leader = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        partyService.createParty(leader);

        // Cannot invite self
        PartyException selfEx = assertThrows(PartyException.class, () -> partyService.invitePlayer(leader, leader));
        assertEquals(PartyException.ErrorCode.CANNOT_INVITE_SELF, selfEx.errorCode());

        // Valid invite
        PartyInvite invite = partyService.invitePlayer(leader, target);
        assertNotNull(invite);
        assertEquals(leader, invite.inviter());
        assertEquals(target, invite.target());
        assertFalse(invite.isExpired(clock.instant()));

        // Non-leader cannot invite
        PartyException notLeaderEx = assertThrows(PartyException.class, () -> partyService.invitePlayer(target, leader));
        assertEquals(PartyException.ErrorCode.PLAYER_NOT_IN_PARTY, notLeaderEx.errorCode());
    }

    @Test
    void testExpiredInviteCannotBeAccepted() {
        UUID leader = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        PartyInvite invite = partyService.invitePlayer(leader, target);
        assertNotNull(invite);

        // Advance past 60s TTL
        clock.advance(Duration.ofSeconds(61));
        assertTrue(invite.isExpired(clock.instant()));

        PartyException ex = assertThrows(PartyException.class, () -> partyService.acceptInvite(target, party.partyId()));
        assertEquals(PartyException.ErrorCode.INVITE_EXPIRED, ex.errorCode());

        // Target did not join
        assertFalse(partyService.partyOf(target).isPresent());
    }

    @Test
    void testInviteReplayRejected() {
        UUID leader = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, target);
        PartySnapshot joined = partyService.acceptInvite(target, party.partyId());
        assertEquals(2, joined.size());

        // Replay accept
        PartyException ex = assertThrows(PartyException.class, () -> partyService.acceptInvite(target, party.partyId()));
        assertEquals(PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY, ex.errorCode());
    }

    @Test
    void testAcceptInviteSuccess() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member);
        PartySnapshot updated = partyService.acceptInvite(member, party.partyId());

        assertEquals(2, updated.size());
        assertTrue(updated.containsMember(member));
        assertEquals(Optional.of(PartyRole.MEMBER), updated.roleOf(member));
        assertEquals(Optional.of(party.partyId()), partyService.partyOf(member).map(PartySnapshot::partyId));

        boolean joinedEventFound = eventsReceived.stream()
                .anyMatch(e -> e instanceof PartyMemberJoinedEvent join && join.playerId().equals(member));
        assertTrue(joinedEventFound);
    }

    @Test
    void testDeclineInvite() {
        UUID leader = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, target);
        partyService.declineInvite(target, party.partyId());

        // Trying to accept after decline should fail with INVITE_NOT_FOUND
        PartyException ex = assertThrows(PartyException.class, () -> partyService.acceptInvite(target, party.partyId()));
        assertEquals(PartyException.ErrorCode.INVITE_NOT_FOUND, ex.errorCode());
    }

    @Test
    void testLeavePartyRegularMember() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member);
        partyService.acceptInvite(member, party.partyId());

        PartySnapshot afterLeave = partyService.leaveParty(member);
        assertEquals(1, afterLeave.size());
        assertFalse(afterLeave.containsMember(member));
        assertFalse(partyService.partyOf(member).isPresent());

        boolean leftEvent = eventsReceived.stream()
                .anyMatch(e -> e instanceof PartyMemberLeftEvent l && l.playerId().equals(member));
        assertTrue(leftEvent);
    }

    @Test
    void testLeaderLeaveTransfersLeadership() {
        UUID leader = UUID.randomUUID();
        UUID member1 = UUID.randomUUID();
        UUID member2 = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member1);
        partyService.acceptInvite(member1, party.partyId());

        partyService.invitePlayer(leader, member2);
        partyService.acceptInvite(member2, party.partyId());

        // Leader leaves: next member in join order (member1) becomes leader
        PartySnapshot afterLeaderLeave = partyService.leaveParty(leader);
        assertEquals(2, afterLeaderLeave.size());
        assertEquals(member1, afterLeaderLeave.leader());
        assertEquals(Optional.of(PartyRole.LEADER), afterLeaderLeave.roleOf(member1));
        assertFalse(afterLeaderLeave.containsMember(leader));

        boolean leaderChangedEvent = eventsReceived.stream()
                .anyMatch(e -> e instanceof PartyLeaderChangedEvent plc && plc.newLeader().equals(member1));
        assertTrue(leaderChangedEvent);
    }

    @Test
    void testLoneLeaderLeaveDisbandsParty() {
        UUID leader = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        PartySnapshot afterLeave = partyService.leaveParty(leader);
        assertEquals(PartyState.DISBANDING, afterLeave.state());
        assertFalse(partyService.partyOf(leader).isPresent());
        assertFalse(partyService.party(party.partyId()).isPresent());
    }

    @Test
    void testTransferLeadership() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member);
        partyService.acceptInvite(member, party.partyId());

        PartySnapshot transferred = partyService.transferLeadership(leader, member);
        assertEquals(member, transferred.leader());
        assertEquals(Optional.of(PartyRole.LEADER), transferred.roleOf(member));
        assertEquals(Optional.of(PartyRole.MEMBER), transferred.roleOf(leader));

        // Member cannot transfer leadership
        PartyException ex = assertThrows(PartyException.class, () -> partyService.transferLeadership(leader, member));
        assertEquals(PartyException.ErrorCode.NOT_PARTY_LEADER, ex.errorCode());

        // Cannot transfer to self
        PartyException selfEx = assertThrows(PartyException.class, () -> partyService.transferLeadership(member, member));
        assertEquals(PartyException.ErrorCode.CANNOT_TRANSFER_TO_SELF, selfEx.errorCode());
    }

    @Test
    void testKickPlayer() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member);
        partyService.acceptInvite(member, party.partyId());

        // Leader cannot kick self
        PartyException kickSelf = assertThrows(PartyException.class, () -> partyService.kickPlayer(leader, leader));
        assertEquals(PartyException.ErrorCode.CANNOT_KICK_LEADER, kickSelf.errorCode());

        // Non-leader cannot kick
        PartyException nonLeaderKick = assertThrows(PartyException.class, () -> partyService.kickPlayer(member, leader));
        assertEquals(PartyException.ErrorCode.NOT_PARTY_LEADER, nonLeaderKick.errorCode());

        // Leader kicks member
        PartySnapshot afterKick = partyService.kickPlayer(leader, member);
        assertEquals(1, afterKick.size());
        assertFalse(afterKick.containsMember(member));
        assertFalse(partyService.partyOf(member).isPresent());
    }

    @Test
    void testDisbandParty() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member);
        partyService.acceptInvite(member, party.partyId());

        // Non-leader cannot disband
        PartyException ex = assertThrows(PartyException.class, () -> partyService.disbandParty(member, party.partyId()));
        assertEquals(PartyException.ErrorCode.NOT_PARTY_LEADER, ex.errorCode());

        // Leader disbands
        PartySnapshot disbanded = partyService.disbandParty(leader, party.partyId());
        assertEquals(PartyState.DISBANDING, disbanded.state());
        assertFalse(partyService.partyOf(leader).isPresent());
        assertFalse(partyService.partyOf(member).isPresent());
        assertFalse(partyService.party(party.partyId()).isPresent());
    }

    @Test
    void testPartyMaxSizeEnforced() {
        // maxSize = 4
        UUID leader = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        for (int i = 0; i < 3; i++) {
            UUID m = UUID.randomUUID();
            partyService.invitePlayer(leader, m);
            partyService.acceptInvite(m, party.partyId());
        }

        assertEquals(4, partyService.party(party.partyId()).orElseThrow().size());

        // Inviting 5th player should fail with PARTY_FULL
        UUID excess = UUID.randomUUID();
        PartyException ex = assertThrows(PartyException.class, () -> partyService.invitePlayer(leader, excess));
        assertEquals(PartyException.ErrorCode.PARTY_FULL, ex.errorCode());
    }

    @Test
    void testOnePartyPerPlayerInvariant() {
        UUID leader1 = UUID.randomUUID();
        UUID leader2 = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        PartySnapshot party1 = partyService.createParty(leader1);
        PartySnapshot party2 = partyService.createParty(leader2);

        partyService.invitePlayer(leader1, target);
        partyService.acceptInvite(target, party1.partyId());

        // Target cannot create another party
        assertThrows(PartyException.class, () -> partyService.createParty(target));

        // Leader 2 cannot invite target (target already in a party)
        PartyException exInvite = assertThrows(PartyException.class, () -> partyService.invitePlayer(leader2, target));
        assertEquals(PartyException.ErrorCode.TARGET_ALREADY_IN_PARTY, exInvite.errorCode());
    }

    @Test
    void testConcurrentAcceptsExactlyOneWins() throws Exception {
        int partyCount = 100;
        UUID target = UUID.randomUUID();

        List<PartySnapshot> parties = new ArrayList<>(partyCount);
        for (int i = 0; i < partyCount; i++) {
            UUID leader = UUID.randomUUID();
            PartySnapshot party = partyService.createParty(leader);
            parties.add(party);
            partyService.invitePlayer(leader, target);
        }

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(partyCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (PartySnapshot party : parties) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    partyService.acceptInvite(target, party.partyId());
                    successCount.incrementAndGet();
                } catch (PartyException ex) {
                    if (ex.errorCode() == PartyException.ErrorCode.PLAYER_ALREADY_IN_PARTY) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly 1 party must succeed!
        assertEquals(1, successCount.get(), "Exactly one accept must succeed");
        assertEquals(99, failureCount.get(), "99 concurrent accepts must fail with PLAYER_ALREADY_IN_PARTY");

        // Target belongs to exactly 1 party
        Optional<PartySnapshot> targetParty = partyService.partyOf(target);
        assertTrue(targetParty.isPresent());
        assertEquals(2, targetParty.get().size());
        assertTrue(targetParty.get().containsMember(target));

        // Invariant: all other 99 parties do NOT contain target
        for (PartySnapshot p : parties) {
            if (!p.partyId().equals(targetParty.get().partyId())) {
                PartySnapshot refreshed = partyService.party(p.partyId()).orElseThrow();
                assertEquals(1, refreshed.size());
                assertFalse(refreshed.containsMember(target));
            }
        }
    }

    @Test
    void testLeaderDisconnectGracePeriodAndLeadershipTransfer() {
        UUID leader = UUID.randomUUID();
        UUID member1 = UUID.randomUUID();
        UUID member2 = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.invitePlayer(leader, member1);
        partyService.acceptInvite(member1, party.partyId());
        partyService.invitePlayer(leader, member2);
        partyService.acceptInvite(member2, party.partyId());

        // Leader disconnects
        partyService.handlePlayerDisconnect(leader);

        // Grace period is 30s. Advance by 15s -> sweep should NOT transfer yet
        clock.advance(Duration.ofSeconds(15));
        partyService.sweep();
        assertEquals(leader, partyService.party(party.partyId()).orElseThrow().leader());

        // Advance past 30s grace period -> sweep transfers leadership to member1
        clock.advance(Duration.ofSeconds(20));
        partyService.sweep();

        PartySnapshot current = partyService.party(party.partyId()).orElseThrow();
        assertEquals(member1, current.leader());
        assertFalse(current.containsMember(leader));
        assertEquals(2, current.size());
    }

    @Test
    void testLoneLeaderDisconnectGracePeriodDisbands() {
        UUID leader = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);

        partyService.handlePlayerDisconnect(leader);
        clock.advance(Duration.ofSeconds(35));
        partyService.sweep();

        assertFalse(partyService.party(party.partyId()).isPresent());
        assertFalse(partyService.partyOf(leader).isPresent());
    }
}

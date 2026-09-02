package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.PartyEvent;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyMemberJoinedEvent;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.common.PartyEventBus;
import com.bigbangcraft.hub.common.PartySettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaperPartyServiceTest {
    private PaperPartyService partyService;
    private PartyEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new PartyEventBus();
        PartySettings settings = new PartySettings(4, Duration.ofSeconds(60), Duration.ofSeconds(15), Duration.ofMillis(500));
        partyService = new PaperPartyService(null, settings, eventBus);
    }

    @Test
    void testPaperPartyLifecycleInFallbackMode() {
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        List<PartyEvent> events = new ArrayList<>();
        partyService.addListener(events::add);

        // 1. Create Party
        PartySnapshot party = partyService.createParty(leaderId);
        assertNotNull(party);
        assertEquals(leaderId, party.leader());
        assertEquals(1, party.size());
        assertTrue(partyService.partyOf(leaderId).isPresent());

        // 2. Invite Player
        PartyInvite invite = partyService.invitePlayer(leaderId, memberId);
        assertNotNull(invite);
        assertEquals(memberId, invite.target());

        // 3. Accept Invite
        PartySnapshot joinedParty = partyService.acceptInvite(memberId, party.partyId());
        assertEquals(2, joinedParty.size());
        assertTrue(partyService.partyOf(memberId).isPresent());
        assertTrue(events.stream().anyMatch(e -> e instanceof PartyMemberJoinedEvent));

        // 4. Transfer Leadership
        PartySnapshot newLeaderParty = partyService.transferLeadership(leaderId, memberId);
        assertEquals(memberId, newLeaderParty.leader());

        // 5. Kick original leader
        PartySnapshot afterKick = partyService.kickPlayer(memberId, leaderId);
        assertEquals(1, afterKick.size());
        assertFalse(partyService.partyOf(leaderId).isPresent());

        // 6. Disband
        PartySnapshot disbanded = partyService.disbandParty(memberId, party.partyId());
        assertTrue(disbanded.state().isTerminal());
        assertFalse(partyService.partyOf(memberId).isPresent());
    }

    @Test
    void testPaperPartyServiceConfigAndCleanup() {
        assertEquals(4, partyService.maxPartySize());
        assertEquals(Duration.ofSeconds(60), partyService.inviteTtl());

        UUID leader = UUID.randomUUID();
        partyService.createParty(leader);
        assertTrue(partyService.partyOf(leader).isPresent());

        partyService.clear();
        assertFalse(partyService.partyOf(leader).isPresent());
    }
}

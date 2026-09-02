package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyInviteExpiredEvent(
        PartyInvite invite,
        Instant timestamp) implements PartyEvent {

    public PartyInviteExpiredEvent {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public PartyId partyId() {
        return invite.partyId();
    }

    public UUID inviter() {
        return invite.inviter();
    }

    public UUID target() {
        return invite.target();
    }
}

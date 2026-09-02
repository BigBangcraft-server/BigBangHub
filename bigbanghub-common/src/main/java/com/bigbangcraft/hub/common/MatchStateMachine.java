package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, atomic state machine governing the match lifecycle.
 * Maintains monotonic revision increments on valid transitions to protect against stale updates.
 */
public final class MatchStateMachine {
    private final MatchId matchId;
    private final AtomicReference<MatchState> state;
    private final AtomicLong revision;
    private final AtomicReference<Instant> startedAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> endedAt = new AtomicReference<>(null);

    public MatchStateMachine(MatchId matchId, MatchState initialState, long initialRevision) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.state = new AtomicReference<>(Objects.requireNonNull(initialState, "initialState"));
        if (initialRevision < 1) {
            throw new IllegalArgumentException("initialRevision must be >= 1");
        }
        this.revision = new AtomicLong(initialRevision);
    }

    public MatchStateMachine(MatchId matchId) {
        this(matchId, MatchState.CREATED, 1L);
    }

    public MatchId matchId() {
        return matchId;
    }

    public MatchState state() {
        return state.get();
    }

    public long revision() {
        return revision.get();
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt.get());
    }

    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt.get());
    }

    public boolean isTerminal() {
        return state.get().isTerminal();
    }

    public boolean canAcceptAdmissions(boolean allowLateJoin) {
        return state.get().canAcceptAdmissions(allowLateJoin);
    }

    /**
     * Atomically transitions state from expected to target if valid.
     * Increments monotonic revision on success.
     *
     * @param expected expected current state
     * @param target target next state
     * @param now timestamp of transition
     * @return true if transitioned; false if state mismatch, invalid transition, or already terminal
     */
    public synchronized boolean transition(MatchState expected, MatchState target, Instant now) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");

        MatchState current = state.get();
        if (current != expected) {
            return false;
        }
        if (current.isTerminal()) {
            return false;
        }
        if (current == target) {
            return true; // idempotent
        }
        if (!current.canTransitionTo(target)) {
            return false;
        }

        state.set(target);
        revision.incrementAndGet();

        if (target == MatchState.IN_GAME && startedAt.get() == null) {
            startedAt.set(now);
        } else if (target.isTerminal() && endedAt.get() == null) {
            endedAt.set(now);
        }

        return true;
    }

    /**
     * Forcibly transitions non-terminal state to ABORTED.
     */
    public synchronized boolean forceAbort(Instant now) {
        Objects.requireNonNull(now, "now");
        MatchState current = state.get();
        if (current.isTerminal()) {
            return false;
        }
        state.set(MatchState.ABORTED);
        revision.incrementAndGet();
        if (endedAt.get() == null) {
            endedAt.set(now);
        }
        return true;
    }
}

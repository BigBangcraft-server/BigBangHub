package com.bigbangcraft.hub.api;

/**
 * Standardized match lifecycle states.
 */
public enum MatchState {
    /**
     * Session created, preparing world/arena/resources.
     */
    CREATED,

    /**
     * Open and accepting player admissions.
     */
    WAITING,

    /**
     * Minimum players reached; start countdown running. Still accepts admissions if policy permits.
     */
    COUNTDOWN,

    /**
     * Roster frozen; no new normal admissions.
     */
    LOCKED,

    /**
     * Active gameplay in progress.
     */
    IN_GAME,

    /**
     * Gameplay completed; calculating results, returning players and running cleanup.
     */
    ENDING,

    /**
     * Match finished successfully; instance cleanup confirmed.
     */
    FINISHED,

    /**
     * Match aborted due to error, shortage of players, or shutdown.
     */
    ABORTED;

    public boolean isTerminal() {
        return this == FINISHED || this == ABORTED;
    }

    public boolean canAcceptAdmissions(boolean allowLateJoin) {
        if (this == WAITING || this == COUNTDOWN) {
            return true;
        }
        return allowLateJoin && (this == LOCKED || this == IN_GAME);
    }

    public boolean canTransitionTo(MatchState target) {
        if (target == null || isTerminal()) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case CREATED -> target == WAITING || target == ABORTED;
            case WAITING -> target == COUNTDOWN || target == ABORTED;
            case COUNTDOWN -> target == WAITING || target == LOCKED || target == ABORTED;
            case LOCKED -> target == IN_GAME || target == ABORTED;
            case IN_GAME -> target == ENDING || target == ABORTED;
            case ENDING -> target == FINISHED || target == ABORTED;
            case FINISHED, ABORTED -> false;
        };
    }
}

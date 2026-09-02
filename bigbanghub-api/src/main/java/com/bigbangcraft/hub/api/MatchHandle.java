package com.bigbangcraft.hub.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MatchHandle {
    MatchId matchId();

    MatchSnapshot snapshot();

    MatchState state();

    long revision();

    Collection<MatchParticipant> participants();

    Optional<MatchParticipant> participant(UUID playerId);

    CompletionStage<Void> open();

    CompletionStage<Void> startCountdown(Duration duration);

    CompletionStage<Void> cancelCountdown();

    CompletionStage<Void> lock();

    CompletionStage<Void> start();

    CompletionStage<Void> eliminate(UUID playerId);

    CompletionStage<Void> setSpectator(UUID playerId);

    CompletionStage<Void> finish(MatchResult result);

    CompletionStage<Void> abort(String reason);

    CompletionStage<Void> markReady();
}

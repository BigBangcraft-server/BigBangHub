package com.bigbangcraft.hub.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MatchManager {
    MatchHandle create(MatchDefinition definition);

    Optional<MatchHandle> currentMatch();

    Optional<MatchSnapshot> activeMatch(ServerId instanceId);

    Optional<MatchSnapshot> match(MatchId matchId);

    Collection<MatchSnapshot> activeMatches();

    Collection<MatchSnapshot> activeMatchesForGame(GameId gameId);

    Optional<MatchSnapshot> matchForPlayer(UUID playerId);

    CompletionStage<Void> abortMatch(MatchId matchId, String reason);
}

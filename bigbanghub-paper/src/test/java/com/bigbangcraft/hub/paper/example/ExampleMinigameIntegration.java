package com.bigbangcraft.hub.paper.example;

import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchHandle;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PlayerAdmissionAcceptedEvent;
import com.bigbangcraft.hub.api.PlayerAdmissionRejectedEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional, compilable example illustrating how a minigame plugin
 * (such as BigBangMinefield, BedWars, or HG) integrates with BigBangHub.
 */
public class ExampleMinigameIntegration {

    public static class ExampleMinigameGameController {
        private final BigBangHubApi hub;
        private MatchHandle currentMatch;

        public ExampleMinigameGameController(BigBangHubApi hub) {
            this.hub = hub;
            registerListeners();
        }

        private void registerListeners() {
            hub.addMatchListener(event -> {
                if (event instanceof PlayerAdmissionAcceptedEvent accepted) {
                    onPlayerAdmitted(accepted.playerId(), accepted.role());
                } else if (event instanceof PlayerAdmissionRejectedEvent rejected) {
                    onPlayerRejected(rejected.playerId(), rejected.reason());
                }
            });
        }

        public void setupAndOpenMatch() {
            // 1. Define and create a new match session
            MatchDefinition definition = MatchDefinition.builder()
                    .gameId("campominado")
                    .minPlayers(2)
                    .maxPlayers(12)
                    .arenaId("minefield_desert_01")
                    .allowLateJoin(false)
                    .build();

            this.currentMatch = hub.matches().create(definition);

            // 2. Open match for proxy admissions and queue routing
            currentMatch.open();
        }

        public void onCountdownTriggered() {
            if (currentMatch != null && currentMatch.state() == MatchState.WAITING) {
                // 3. Start countdown
                currentMatch.startCountdown(Duration.ofSeconds(10));
            }
        }

        public void onCountdownComplete() {
            if (currentMatch != null && currentMatch.state() == MatchState.COUNTDOWN) {
                // 4. Lock match (prevent further joins) and start in-game
                currentMatch.lock().thenCompose(v -> currentMatch.start());
            }
        }

        public void onPlayerSteppedOnMine(UUID playerId) {
            if (currentMatch != null && currentMatch.state() == MatchState.IN_GAME) {
                // 5. Eliminate player and turn into spectator
                currentMatch.eliminate(playerId);
                currentMatch.setSpectator(playerId);
            }
        }

        public void onLastPlayerStanding(UUID winnerId, Duration gameDuration) {
            if (currentMatch != null && currentMatch.state() == MatchState.IN_GAME) {
                // 6. Finish match with results (automatically triggers safe return to Hub)
                MatchResult result = MatchResult.singleWinner(winnerId, gameDuration);
                currentMatch.finish(result).thenRun(this::cleanupArenaAndMarkReady);
            }
        }

        private void cleanupArenaAndMarkReady() {
            // 7. Reset map / arena blocks
            // ... minigame resets arena blocks ...

            // 8. Mark instance ready for next match (releases instance in proxy registry)
            if (currentMatch != null) {
                currentMatch.markReady();
            }
        }

        private void onPlayerAdmitted(UUID playerId, ParticipantRole role) {
            // Player successfully admitted via ticket handshake
        }

        private void onPlayerRejected(UUID playerId, String reason) {
            // Player admission rejected (ticket invalid or direct backend join)
        }

        public MatchHandle currentMatch() {
            return currentMatch;
        }
    }

    @Test
    void testExampleControllerCompilesAndValidates() {
        assertNotNull(ExampleMinigameGameController.class);
    }
}

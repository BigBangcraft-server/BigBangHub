package com.bigbangcraft.hub.api;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MatchResult(
        Outcome outcome,
        Set<UUID> winnerIds,
        Duration duration,
        Map<String, String> metadata) {

    public enum Outcome {
        WIN,
        DRAW,
        ABORTED
    }

    public static final int MAX_METADATA_ENTRIES = 16;
    public static final int MAX_KEY_LENGTH = 32;
    public static final int MAX_VALUE_LENGTH = 128;

    public MatchResult {
        Objects.requireNonNull(outcome, "outcome");
        winnerIds = winnerIds != null ? Set.copyOf(winnerIds) : Collections.emptySet();
        duration = duration != null ? duration : Duration.ZERO;
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        if (metadata == null) {
            metadata = Collections.emptyMap();
        } else {
            if (metadata.size() > MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("Metadata cannot exceed " + MAX_METADATA_ENTRIES + " entries");
            }
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getKey() == null || entry.getKey().length() > MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException("Metadata key too long (max " + MAX_KEY_LENGTH + "): " + entry.getKey());
                }
                if (entry.getValue() == null || entry.getValue().length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("Metadata value too long (max " + MAX_VALUE_LENGTH + ")");
                }
            }
            metadata = Map.copyOf(metadata);
        }
    }

    public static MatchResult singleWinner(UUID winnerId, Duration duration) {
        Objects.requireNonNull(winnerId, "winnerId");
        return new MatchResult(Outcome.WIN, Set.of(winnerId), duration, Collections.emptyMap());
    }

    public static MatchResult winners(Set<UUID> winners, Duration duration) {
        return new MatchResult(Outcome.WIN, winners, duration, Collections.emptyMap());
    }

    public static MatchResult draw(Duration duration) {
        return new MatchResult(Outcome.DRAW, Collections.emptySet(), duration, Collections.emptyMap());
    }

    public static MatchResult aborted(String reason) {
        return new MatchResult(Outcome.ABORTED, Collections.emptySet(), Duration.ZERO,
                reason != null ? Map.of("reason", reason) : Collections.emptyMap());
    }
}

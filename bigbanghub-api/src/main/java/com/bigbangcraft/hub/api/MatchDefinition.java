package com.bigbangcraft.hub.api;

import java.util.Objects;
import java.util.Optional;

public record MatchDefinition(
        GameId gameId,
        int minPlayers,
        int maxPlayers,
        Optional<String> arenaId,
        DisconnectPolicy disconnectPolicy,
        boolean allowLateJoin) {

    public MatchDefinition {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(arenaId, "arenaId");
        Objects.requireNonNull(disconnectPolicy, "disconnectPolicy");
        if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
        if (maxPlayers > 1000) throw new IllegalArgumentException("maxPlayers cannot exceed 1000");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private GameId gameId;
        private int minPlayers = 2;
        private int maxPlayers = 10;
        private String arenaId;
        private DisconnectPolicy disconnectPolicy = DisconnectPolicy.REMOVE;
        private boolean allowLateJoin = false;

        public Builder gameId(GameId gameId) {
            this.gameId = gameId;
            return this;
        }

        public Builder gameId(String gameId) {
            this.gameId = GameId.of(gameId);
            return this;
        }

        public Builder minPlayers(int minPlayers) {
            this.minPlayers = minPlayers;
            return this;
        }

        public Builder maxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        public Builder arenaId(String arenaId) {
            this.arenaId = arenaId;
            return this;
        }

        public Builder disconnectPolicy(DisconnectPolicy disconnectPolicy) {
            this.disconnectPolicy = disconnectPolicy;
            return this;
        }

        public Builder allowLateJoin(boolean allowLateJoin) {
            this.allowLateJoin = allowLateJoin;
            return this;
        }

        public MatchDefinition build() {
            return new MatchDefinition(
                    Objects.requireNonNull(gameId, "gameId must be set"),
                    minPlayers,
                    maxPlayers,
                    Optional.ofNullable(arenaId),
                    disconnectPolicy,
                    allowLateJoin);
        }
    }
}

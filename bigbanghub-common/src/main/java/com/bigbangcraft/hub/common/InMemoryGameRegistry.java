package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class InMemoryGameRegistry implements GameRegistry {
    private final Map<GameId, GameDefinition> games;

    public InMemoryGameRegistry(Collection<GameDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        this.games = definitions.stream().collect(Collectors.toUnmodifiableMap(
                GameDefinition::id, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException("Duplicate game: " + left.id());
                }));
    }

    @Override
    public Collection<GameDefinition> games() {
        return List.copyOf(games.values());
    }

    @Override
    public Optional<GameDefinition> find(GameId id) {
        return Optional.ofNullable(games.get(Objects.requireNonNull(id, "id")));
    }
}

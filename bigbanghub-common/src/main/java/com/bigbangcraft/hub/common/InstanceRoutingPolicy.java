package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.RoutingStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@FunctionalInterface
public interface InstanceRoutingPolicy {
    Optional<InstanceSnapshot> select(List<InstanceSnapshot> eligible);

    Comparator<InstanceSnapshot> TIE_BREAK = Comparator.comparing(snap -> snap.instanceId().value());

    Comparator<InstanceSnapshot> FILL_WAITING_COMPARATOR = Comparator
            .comparingInt((InstanceSnapshot s) -> s.playerCount() + s.activeReservations()).reversed()
            .thenComparing(TIE_BREAK);

    Comparator<InstanceSnapshot> LEAST_PLAYERS_COMPARATOR = Comparator
            .comparingInt((InstanceSnapshot s) -> s.playerCount() + s.activeReservations())
            .thenComparing(TIE_BREAK);

    InstanceRoutingPolicy FILL_WAITING = eligible -> {
        if (eligible == null || eligible.isEmpty()) return Optional.empty();
        return eligible.stream().min(FILL_WAITING_COMPARATOR);
    };

    InstanceRoutingPolicy LEAST_PLAYERS = eligible -> {
        if (eligible == null || eligible.isEmpty()) return Optional.empty();
        return eligible.stream().min(LEAST_PLAYERS_COMPARATOR);
    };

    static InstanceRoutingPolicy roundRobin() {
        AtomicInteger counter = new AtomicInteger(0);
        return eligible -> {
            if (eligible == null || eligible.isEmpty()) return Optional.empty();
            List<InstanceSnapshot> sorted = eligible.stream().sorted(TIE_BREAK).toList();
            int idx = (counter.getAndIncrement() & 0x7fffffff) % sorted.size();
            return Optional.of(sorted.get(idx));
        };
    }

    static InstanceRoutingPolicy forStrategy(RoutingStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return switch (strategy) {
            case FILL_WAITING -> FILL_WAITING;
            case LEAST_PLAYERS -> LEAST_PLAYERS;
            case ROUND_ROBIN -> roundRobin();
        };
    }
}

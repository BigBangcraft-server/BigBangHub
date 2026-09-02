package com.bigbangcraft.hub.api;

import java.util.Optional;
import java.util.function.Consumer;

/** Stable integration surface exposed through the Paper/Velocity platform service mechanisms. */
public interface BigBangHubApi {
    default ServerRole role() {
        return ServerRole.GENERIC;
    }

    GameRegistry games();

    ServerRegistry servers();

    QueueService queues();

    RoutingService routing();

    PlayerTransferService transfers();

    default InstanceRegistry instances() {
        throw new UnsupportedOperationException("Instance registry not supported on this platform");
    }

    default Optional<InstanceService> instance() {
        return Optional.empty();
    }

    void addQueueListener(Consumer<QueueEvent> listener);

    void removeQueueListener(Consumer<QueueEvent> listener);

    default void addInstanceListener(Consumer<InstanceEvent> listener) {
    }

    default void removeInstanceListener(Consumer<InstanceEvent> listener) {
    }
}

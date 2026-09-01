package com.bigbangcraft.hub.api;

/** Stable integration surface exposed through the Paper/Velocity platform service mechanisms. */
public interface BigBangHubApi {
    GameRegistry games();

    ServerRegistry servers();

    QueueService queues();

    RoutingService routing();

    PlayerTransferService transfers();

    void addQueueListener(java.util.function.Consumer<QueueEvent> listener);

    void removeQueueListener(java.util.function.Consumer<QueueEvent> listener);
}

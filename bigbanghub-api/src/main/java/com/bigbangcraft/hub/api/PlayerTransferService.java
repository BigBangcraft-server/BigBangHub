package com.bigbangcraft.hub.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface PlayerTransferService {
    CompletionStage<TransferResult> transfer(UUID playerId, ServerId serverId);
}

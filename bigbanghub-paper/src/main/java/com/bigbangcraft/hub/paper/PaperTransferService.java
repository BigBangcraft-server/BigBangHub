package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.TransferResult;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

final class PaperTransferService implements PlayerTransferService {
    private final VelocityBridge bridge;

    PaperTransferService(VelocityBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletionStage<TransferResult> transfer(UUID playerId, ServerId serverId) {
        return bridge.request(playerId, MessageType.SERVER_CONNECT, MessagePayloads.serverConnect(playerId, serverId))
                .thenApply(envelope -> {
                    try {
                        MessagePayloads.ServerResponse response = MessagePayloads.serverResponse(envelope.payload());
                        return response.playerId().equals(playerId)
                                ? new TransferResult(response.success(), response.message())
                                : TransferResult.failure("Resposta inválida do proxy.");
                    } catch (Exception exception) {
                        return TransferResult.failure("Resposta inválida do proxy.");
                    }
                });
    }
}

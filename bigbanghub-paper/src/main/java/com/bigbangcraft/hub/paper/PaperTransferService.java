package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.TransferResult;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolValidationException;

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
                    } catch (ProtocolValidationException | IllegalArgumentException exception) {
                        return TransferResult.failure("Resposta inválida do proxy.");
                    }
                });
    }

    public CompletionStage<TransferResult> returnToHub(UUID playerId, ReturnReason reason, String message) {
        MessagePayloads.ReturnReasonWire wireReason = switch (reason) {
            case MATCH_FINISHED -> MessagePayloads.ReturnReasonWire.MATCH_FINISHED;
            case MATCH_ABORTED -> MessagePayloads.ReturnReasonWire.MATCH_ABORTED;
            case PLAYER_ELIMINATED -> MessagePayloads.ReturnReasonWire.PLAYER_ELIMINATED;
            case PLAYER_LEFT -> MessagePayloads.ReturnReasonWire.PLAYER_LEFT;
            case SERVER_FAILURE -> MessagePayloads.ReturnReasonWire.SERVER_FAILURE;
            case ADMIN_FORCE_RETURN -> MessagePayloads.ReturnReasonWire.ADMIN_FORCE_RETURN;
            case DIRECT_JOIN_REJECTED -> MessagePayloads.ReturnReasonWire.DIRECT_JOIN_REJECTED;
        };
        bridge.sendAny(MessageType.PLAYER_RETURN,
                MessagePayloads.playerReturn(new MessagePayloads.PlayerReturn(playerId, wireReason, message != null ? message : "")));
        return bridge.request(playerId, MessageType.SERVER_CONNECT,
                MessagePayloads.serverConnect(playerId, ServerId.of("hubminigame")))
                .thenApply(envelope -> {
                    try {
                        MessagePayloads.ServerResponse response = MessagePayloads.serverResponse(envelope.payload());
                        return new TransferResult(response.success(), response.message());
                    } catch (Exception e) {
                        return TransferResult.failure("Falha no retorno ao hub.");
                    }
                });
    }
}

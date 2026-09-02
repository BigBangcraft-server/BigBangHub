package com.bigbangcraft.hub.common;

import java.util.Objects;

public record ProxySettings(String channel, int protocolVersion, String hubServerName,
                            String sharedSecretEnvironment, boolean requireHmac, int maxPayloadBytes) {
    public ProxySettings {
        channel = Objects.requireNonNull(channel, "channel");
        if (protocolVersion != ProtocolCodec.PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + protocolVersion);
        }
        hubServerName = Objects.requireNonNull(hubServerName, "hubServerName");
        sharedSecretEnvironment = Objects.requireNonNull(sharedSecretEnvironment, "sharedSecretEnvironment");
        if (maxPayloadBytes < 256 || maxPayloadBytes > ProtocolCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid maxPayloadBytes: " + maxPayloadBytes);
        }
    }
}

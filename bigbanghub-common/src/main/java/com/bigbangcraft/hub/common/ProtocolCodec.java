package com.bigbangcraft.hub.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

public final class ProtocolCodec {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final int MAGIC = 0x42424831;
    private static final int HMAC_BYTES = 32;
    private final byte[] secret;
    private final int maxPayloadBytes;

    public ProtocolCodec(byte[] secret, int maxPayloadBytes, boolean requireHmac) {
        this.secret = Objects.requireNonNull(secret, "secret").clone();
        if (maxPayloadBytes < 256 || maxPayloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid maxPayloadBytes: " + maxPayloadBytes);
        }
        if (requireHmac && this.secret.length == 0) {
            throw new IllegalArgumentException("HMAC is required but no secret was supplied");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public byte[] encode(ProtocolEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        byte[] payload = envelope.payload();
        if (payload.length > maxPayloadBytes) throw new IllegalArgumentException("Payload is too large");
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(64 + payload.length);
            DataOutputStream body = new DataOutputStream(bodyBytes);
            body.writeInt(MAGIC);
            body.writeByte(envelope.protocolVersion());
            body.writeByte(envelope.messageType().code());
            body.writeLong(envelope.correlationId().getMostSignificantBits());
            body.writeLong(envelope.correlationId().getLeastSignificantBits());
            body.writeInt(payload.length);
            body.write(payload);
            body.flush();
            byte[] unsigned = bodyBytes.toByteArray();
            byte[] signature = sign(unsigned);
            ByteArrayOutputStream resultBytes = new ByteArrayOutputStream(unsigned.length + 2 + signature.length);
            DataOutputStream result = new DataOutputStream(resultBytes);
            result.write(unsigned);
            result.writeShort(signature.length);
            result.write(signature);
            result.flush();
            return resultBytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode protocol envelope", exception);
        }
    }

    public ProtocolEnvelope decode(byte[] raw) throws ProtocolValidationException {
        Objects.requireNonNull(raw, "raw");
        if (raw.length < 4 + 1 + 1 + 16 + 4 + 2 || raw.length > maxPayloadBytes + 64) {
            throw new ProtocolValidationException("Invalid message size: " + raw.length);
        }
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
            DataInputStream input = new DataInputStream(bytes);
            int magic = input.readInt();
            if (magic != MAGIC) throw new ProtocolValidationException("Invalid protocol magic");
            int version = input.readUnsignedByte();
            if (version != PROTOCOL_VERSION) throw new ProtocolValidationException("Unknown protocol version: " + version);
            MessageType type = MessageType.fromCode(input.readUnsignedByte());
            java.util.UUID correlationId = new java.util.UUID(input.readLong(), input.readLong());
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
                throw new ProtocolValidationException("Invalid payload size: " + payloadLength);
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) throw new ProtocolValidationException("Truncated payload");
            int signatureLength = input.readUnsignedShort();
            if (signatureLength != 0 && signatureLength != HMAC_BYTES) {
                throw new ProtocolValidationException("Invalid signature size");
            }
            byte[] signature = input.readNBytes(signatureLength);
            if (signature.length != signatureLength || input.available() != 0) {
                throw new ProtocolValidationException("Trailing or truncated protocol data");
            }
            int unsignedLength = raw.length - 2 - signatureLength;
            byte[] unsigned = java.util.Arrays.copyOf(raw, unsignedLength);
            verify(unsigned, signature);
            return new ProtocolEnvelope(version, type, correlationId, payload);
        } catch (EOFException exception) {
            throw new ProtocolValidationException("Truncated protocol message", exception);
        } catch (IOException exception) {
            throw new ProtocolValidationException("Malformed protocol message", exception);
        }
    }

    public boolean hasSameAuthentication(ProtocolCodec other) {
        return other != null && MessageDigest.isEqual(secret, other.secret)
                && maxPayloadBytes == other.maxPayloadBytes;
    }

    private byte[] sign(byte[] unsigned) {
        if (secret.length == 0) return new byte[0];
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(unsigned);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC is unavailable", exception);
        }
    }

    private void verify(byte[] unsigned, byte[] actual) throws ProtocolValidationException {
        byte[] expected = sign(unsigned);
        if (secret.length == 0) {
            if (actual.length != 0) throw new ProtocolValidationException("Unexpected authentication signature");
            return;
        }
        if (actual.length != HMAC_BYTES || !MessageDigest.isEqual(expected, actual)) {
            throw new ProtocolValidationException("Invalid authentication signature");
        }
    }

    public static byte[] secretFromEnvironment(String variable, boolean required) throws ConfigException {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            if (required) throw new ConfigException("Required protocol secret environment variable is missing: " + variable);
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
}

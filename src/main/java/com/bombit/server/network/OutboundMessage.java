package com.bombit.server.network;

import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;

public record OutboundMessage(short type, byte[] payload) {
    public OutboundMessage {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static OutboundMessage fromPacket(Packet packet) {
        return new OutboundMessage(packet.getType(), packet.getJsonPayload().getBytes(StandardCharsets.UTF_8));
    }

    public Packet toPacket() throws ProtocolException {
        try {
            return new Packet(type, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString());
        } catch (CharacterCodingException invalid) {
            throw new ProtocolException("Invalid outbound UTF-8", invalid);
        }
    }
}

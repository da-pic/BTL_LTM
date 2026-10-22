package com.bombit.common.protocol;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Protocol-2 match header around a canonical JSON object. Inner codecs remain unchanged.
 * This validates the header, type and frame size, not arbitrary inner JSON syntax.
 * Call the message-specific codec after unwrap, before acting on the payload.
 */
public final class MatchEnvelopeCodec {
    private static final String PREFIX = "{\"matchId\":\"";

    private MatchEnvelopeCodec() { }

    public static boolean isMatchScoped(short type) {
        return type == PacketType.END_GAME || type == PacketType.MOVE || type == PacketType.PLANT_BOMB
                || type == PacketType.GAME_SNAPSHOT || type == PacketType.FULL_STATE_SYNC
                || type == PacketType.REQUEST_FULL_STATE || type == PacketType.GAME_OVER
                || type == PacketType.BOMB_PLANTED || type == PacketType.BOMB_EXPLODE
                || type == PacketType.ITEM_SPAWN || type == PacketType.ITEM_PICKED_UP
                || type == PacketType.PLAYER_DIE || type == PacketType.PLAYER_DISCONNECTED;
    }

    public static Packet wrap(UUID matchId, Packet inner) throws IOException {
        Objects.requireNonNull(matchId, "matchId");
        requireScoped(inner);
        String json = inner.getJsonPayload();
        if (!json.startsWith("{") || !json.endsWith("}") || json.contains("\"matchId\"")) {
            throw new ProtocolException("Expected an unscoped JSON object");
        }
        String tail = json.substring(1);
        Packet scoped = new Packet(inner.getType(), PREFIX + matchId + "\"" + (tail.equals("}") ? "}" : "," + tail));
        PacketEncoder.encode(scoped); // Apply the existing byte limit, including the new header.
        return scoped;
    }

    /** Reject an old match before handing its body to a gameplay decoder/replica. */
    public static Packet unwrap(Packet scoped, UUID expectedMatch) throws IOException {
        Objects.requireNonNull(expectedMatch, "expectedMatch");
        requireScoped(scoped);
        PacketEncoder.encode(scoped);
        String json = scoped.getJsonPayload();
        String expected = PREFIX + expectedMatch + "\"";
        if (!json.startsWith(expected) || !json.endsWith("}")) {
            throw new ProtocolException("Missing or unexpected matchId");
        }
        String tail = json.substring(expected.length());
        if (!tail.equals("}") && !tail.startsWith(",")) throw new ProtocolException("Invalid match header");
        String inner = tail.equals("}") ? "{}" : "{" + tail.substring(1);
        if (inner.contains("\"matchId\"")) throw new ProtocolException("Duplicate match header");
        return new Packet(scoped.getType(), inner);
    }

    private static void requireScoped(Packet packet) throws ProtocolException {
        Objects.requireNonNull(packet, "packet");
        if (!isMatchScoped(packet.getType())) throw new ProtocolException("Not a match-scoped type");
    }
}

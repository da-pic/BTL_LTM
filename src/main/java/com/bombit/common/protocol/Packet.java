package com.bombit.common.protocol;

import java.util.Objects;

/** Một message gồm mã loại 16-bit và payload JSON UTF-8 (không bao gồm framing TCP). */
public final class Packet {
    private final short type;
    private final String jsonPayload;

    public Packet(short type, String jsonPayload) {
        this.type = type;
        this.jsonPayload = jsonPayload == null ? "{}" : jsonPayload;
    }

    public short getType() { return type; }
    public String getJsonPayload() { return jsonPayload; }

    @Override public String toString() {
        return "Packet{type=" + PacketType.nameOf(type) + ", code=0x"
                + String.format("%04X", Short.toUnsignedInt(type)) + ", payload=" + jsonPayload + "}";
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Packet packet)) return false;
        return type == packet.type && jsonPayload.equals(packet.jsonPayload);
    }

    @Override public int hashCode() { return Objects.hash(type, jsonPayload); }
}

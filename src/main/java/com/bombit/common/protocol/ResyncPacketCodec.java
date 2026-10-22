package com.bombit.common.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical request body; wrap with MatchEnvelopeCodec before sending on protocol 2. */
public final class ResyncPacketCodec {
    public enum Reason { COLD_START, BASELINE_MISMATCH, RECONNECT }
    public record Request(long lastAppliedTick, Reason reason) {
        public Request {
            if (lastAppliedTick < -1) throw new IllegalArgumentException("Invalid baseline tick");
            Objects.requireNonNull(reason, "reason");
        }
    }
    private static final Pattern FORMAT = Pattern.compile(
            "\\{\"lastAppliedTick\":(-1|0|[1-9][0-9]{0,18}),\"reason\":\"(COLD_START|BASELINE_MISMATCH|RECONNECT)\"\\}");
    private ResyncPacketCodec() { }

    public static Packet encode(Request request) {
        Objects.requireNonNull(request, "request");
        return new Packet(PacketType.REQUEST_FULL_STATE, "{\"lastAppliedTick\":" + request.lastAppliedTick()
                + ",\"reason\":\"" + request.reason() + "\"}");
    }

    public static Request decode(Packet packet) {
        if (packet.getType() != PacketType.REQUEST_FULL_STATE || packet.getJsonPayload().length() > 128) {
            throw new IllegalArgumentException("Invalid resync request");
        }
        var match = FORMAT.matcher(packet.getJsonPayload());
        if (!match.matches()) throw new IllegalArgumentException("Invalid resync body");
        return new Request(Long.parseLong(match.group(1)), Reason.valueOf(match.group(2)));
    }
}

package com.bombit.common.protocol;

import com.bombit.common.dto.MatchContext;
import com.bombit.common.dto.MapTheme;
import java.util.*;
import java.util.regex.Pattern;

/** Canonical GAME_STARTED for the fixed-room M1 profile. No lobby JSON parser duplication. */
public final class DemoPacketCodec {
    private static final String UUID_TEXT = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
    private static final Pattern START = Pattern.compile("\\{\"roomId\":\"" + UUID_TEXT
            + "\",\"matchId\":\"" + UUID_TEXT + "\",\"tickMillis\":50,\"inputStepMillis\":50,\"mapId\":\"([A-Z]+)\",\"players\":\\[(.*)\\]\\}");
    private static final Pattern PLAYER = Pattern.compile("\\{\"sessionId\":\"" + UUID_TEXT
            + "\",\"playerId\":([1-4]),\"username\":\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"\\}");
    private DemoPacketCodec() { }

    public static Packet started(MatchContext context, Map<String, String> names) {
        var json = new StringBuilder("{\"roomId\":\"").append(context.roomId()).append("\",\"matchId\":\"")
                .append(context.matchId()).append("\",\"tickMillis\":50,\"inputStepMillis\":50,\"mapId\":\"")
                .append(context.mapTheme().name()).append("\",\"players\":[");
        for (var player : context.players()) {
            if (json.charAt(json.length() - 1) != '[') json.append(',');
            json.append("{\"sessionId\":\"").append(player.sessionId()).append("\",\"playerId\":")
                    .append(player.playerId()).append(",\"username\":")
                    .append(SessionPacketCodec.quote(Objects.requireNonNull(names.get(player.sessionId())))).append('}');
        }
        return new Packet(PacketType.GAME_STARTED, json.append("]}").toString());
    }

    public static MatchContext decodeStarted(Packet packet) {
        if (packet.getType() != PacketType.GAME_STARTED || packet.getJsonPayload().length() > 4096) {
            throw new IllegalArgumentException("Invalid game metadata");
        }
        var start = START.matcher(packet.getJsonPayload());
        if (!start.matches()) throw new IllegalArgumentException("Invalid game metadata");
        MapTheme theme;
        try { theme=MapTheme.valueOf(start.group(3)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid map theme",invalid); }
        String roster = start.group(4);
        var players = new ArrayList<MatchContext.PlayerIdentity>();
        int pos = 0;
        while (pos < roster.length()) {
            var player = PLAYER.matcher(roster).region(pos, roster.length());
            if (!player.lookingAt()) throw new IllegalArgumentException("Invalid roster");
            players.add(new MatchContext.PlayerIdentity(player.group(1), Integer.parseInt(player.group(2))));
            pos = player.end();
            if (pos < roster.length()) {
                if (roster.charAt(pos++) != ',' || pos == roster.length()) throw new IllegalArgumentException("Invalid roster separator");
            }
        }
        return new MatchContext(start.group(1), UUID.fromString(start.group(2)), theme, players);
    }

    public static Packet waiting(String roomId, int joined, int expected) {
        return new Packet(PacketType.ROOM_STATE, "{\"profile\":\"MOVEMENT_DEMO\",\"roomId\":\"" + roomId + "\",\"state\":\"WAITING\",\"joined\":"
                + joined + ",\"expected\":" + expected + "}");
    }
}

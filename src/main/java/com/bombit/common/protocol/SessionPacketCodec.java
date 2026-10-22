package com.bombit.common.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Small flat-object codec for CONNECT/PING/DISCONNECT. Rejects duplicate/unknown fields. */
public final class SessionPacketCodec {
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\"|-?(?:0|[1-9][0-9]*)|true|false|null)\\s*");
    public record Connect(String playerName, int protocolVersion) { }
    public record Acknowledgement(String playerId, String reconnectToken) { }
    private SessionPacketCodec() { }

    public static Connect connect(Packet packet) {
        var fields = object(packet, PacketType.CONNECT, Set.of("playerName", "protocolVersion"));
        Object name = fields.get("playerName"), version = fields.get("protocolVersion");
        if (!(name instanceof String text) || !(version instanceof Long number)
                || number < 0 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid CONNECT fields");
        if (text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid player name");
        text = text.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 32
                || text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid player name");
        return new Connect(text, number.intValue());
    }

    public static Packet connectRequest(String name) {
        return new Packet(PacketType.CONNECT, "{\"playerName\":" + quote(name)
                + ",\"protocolVersion\":" + IntegrationContract.PROTOCOL_VERSION + "}");
    }

    public static Acknowledgement decodeAcknowledgement(Packet packet) {
        var fields = object(packet, PacketType.CONNECT_ACK, Set.of("playerId", "reconnectToken", "serverVersion",
                "protocolVersion", "heartbeatIntervalMs", "heartbeatTimeoutMs", "reconnectGraceMs"));
        if (!Long.valueOf(IntegrationContract.PROTOCOL_VERSION).equals(fields.get("protocolVersion"))) {
            throw new IllegalArgumentException("Unsupported acknowledgement");
        }
        var id = java.util.UUID.fromString((String) fields.get("playerId"));
        var token = java.util.UUID.fromString((String) fields.get("reconnectToken"));
        return new Acknowledgement(id.toString(), token.toString());
    }

    public static int pingId(Packet packet) {
        Object id = object(packet, PacketType.PING, Set.of("pingId")).get("pingId");
        if (!(id instanceof Long n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid pingId");
        }
        return n.intValue();
    }

    public static void disconnect(Packet packet) {
        if (!"USER_QUIT".equals(object(packet, PacketType.DISCONNECT, Set.of("reason")).get("reason"))) {
            throw new IllegalArgumentException("Invalid disconnect reason");
        }
    }

    public static String errorCode(Packet packet) {
        Object code = object(packet, PacketType.ERROR, Set.of("errorCode", "message", "requestId")).get("errorCode");
        if (!(code instanceof String text)) throw new IllegalArgumentException("Invalid error code");
        return text;
    }

    public static Packet acknowledgement(String playerId, String token) {
        return new Packet(PacketType.CONNECT_ACK, "{\"playerId\":" + quote(playerId)
                + ",\"reconnectToken\":" + quote(token) + ",\"serverVersion\":\"0.1.0\",\"protocolVersion\":"
                + IntegrationContract.PROTOCOL_VERSION + ",\"heartbeatIntervalMs\":" + IntegrationContract.HEARTBEAT_INTERVAL_MILLIS
                + ",\"heartbeatTimeoutMs\":" + IntegrationContract.HEARTBEAT_TIMEOUT_MILLIS
                + ",\"reconnectGraceMs\":" + IntegrationContract.RECONNECT_GRACE_MILLIS + "}");
    }

    public static Packet error(String code) {
        return new Packet(PacketType.ERROR, "{\"errorCode\":" + quote(code)
                + ",\"message\":" + quote(code) + ",\"requestId\":null}");
    }

    private static Map<String, Object> object(Packet packet, short type, Set<String> expected) {
        String json = packet.getJsonPayload().strip();
        if (packet.getType() != type || json.length() > 1024 || !json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("Invalid control object");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        int pos = 1, end = json.length() - 1;
        while (pos < end && Character.isWhitespace(json.charAt(pos))) pos++;
        while (pos < end) {
            var key = TOKEN.matcher(json).region(pos, end);
            if (!key.lookingAt() || !key.group(1).startsWith("\"")) throw new IllegalArgumentException("Expected key");
            String name = string(key.group(1)); pos = key.end();
            if (pos >= end || json.charAt(pos++) != ':') throw new IllegalArgumentException("Expected colon");
            var value = TOKEN.matcher(json).region(pos, end);
            if (!value.lookingAt()) throw new IllegalArgumentException("Expected value");
            String raw = value.group(1);
            Object parsed = raw.startsWith("\"") ? string(raw) : raw.equals("null") ? null
                    : raw.equals("true") ? Boolean.TRUE : raw.equals("false") ? Boolean.FALSE : Long.valueOf(raw);
            if (fields.containsKey(name)) throw new IllegalArgumentException("Duplicate field");
            fields.put(name, parsed); pos = value.end();
            if (pos == end) break;
            if (json.charAt(pos++) != ',') throw new IllegalArgumentException("Expected comma");
            int next = pos;
            while (next < end && Character.isWhitespace(json.charAt(next))) next++;
            if (next == end) throw new IllegalArgumentException("Trailing comma");
        }
        if (!fields.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected fields");
        return fields;
    }

    private static String string(String raw) {
        var result = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char c = raw.charAt(i);
            if (c == '\\') {
                c = raw.charAt(++i);
                if (c == 'u') { c = (char) Integer.parseInt(raw.substring(i + 1, i + 5), 16); i += 4; }
                else c = switch (c) { case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; };
            }
            result.append(c);
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
        }
        return result.toString();
    }

    public static String quote(String text) {
        var result = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}

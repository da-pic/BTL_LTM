package com.bombit.common.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mã thông điệp dùng chung giữa client và server; các giá trị là một phần của protocol. */
public final class PacketType {
    private PacketType() { }

    public static final short CONNECT = 0x0001;
    public static final short CONNECT_ACK = 0x0002;
    public static final short CREATE_ROOM = 0x0010;
    public static final short JOIN_ROOM = 0x0011;
    public static final short ROOM_STATE = 0x0012;
    public static final short READY = 0x0013;
    public static final short START_GAME = 0x0014;
    public static final short GAME_STARTED = 0x0015;
    public static final short LEAVE_ROOM = 0x0016;
    public static final short CHAT = 0x0017;
    public static final short MOVE = 0x0020;
    public static final short PLANT_BOMB = 0x0021;
    public static final short BOMB_PLANTED = 0x0022;
    public static final short BOMB_EXPLODE = 0x0023;
    public static final short ITEM_SPAWN = 0x0024;
    public static final short ITEM_PICKED_UP = 0x0025;
    public static final short PLAYER_DIE = 0x0026;
    public static final short GAME_SNAPSHOT = 0x0030;
    public static final short GAME_OVER = 0x0031;
    public static final short PLAYER_DISCONNECTED = 0x0032;
    public static final short FULL_STATE_SYNC = 0x0033;
    public static final short PING = 0x0040;
    public static final short PONG = 0x0041;
    public static final short DISCONNECT = 0x0042;
    public static final short RECONNECT = 0x0043;
    /** Thông báo mất mạng có thể gửi best-effort khi socket vẫn còn khả năng truyền. */
    public static final short NETWORK_LOST = 0x0044;
    public static final short ERROR = (short) 0x00FF;

    private static final Map<Short, String> NAMES;
    static {
        Map<Short, String> names = new LinkedHashMap<>();
        names.put(CONNECT, "CONNECT"); names.put(CONNECT_ACK, "CONNECT_ACK");
        names.put(CREATE_ROOM, "CREATE_ROOM"); names.put(JOIN_ROOM, "JOIN_ROOM");
        names.put(ROOM_STATE, "ROOM_STATE"); names.put(READY, "READY");
        names.put(START_GAME, "START_GAME"); names.put(GAME_STARTED, "GAME_STARTED");
        names.put(LEAVE_ROOM, "LEAVE_ROOM"); names.put(CHAT, "CHAT");
        names.put(MOVE, "MOVE"); names.put(PLANT_BOMB, "PLANT_BOMB");
        names.put(BOMB_PLANTED, "BOMB_PLANTED"); names.put(BOMB_EXPLODE, "BOMB_EXPLODE");
        names.put(ITEM_SPAWN, "ITEM_SPAWN"); names.put(ITEM_PICKED_UP, "ITEM_PICKED_UP");
        names.put(PLAYER_DIE, "PLAYER_DIE"); names.put(GAME_SNAPSHOT, "GAME_SNAPSHOT");
        names.put(GAME_OVER, "GAME_OVER"); names.put(PLAYER_DISCONNECTED, "PLAYER_DISCONNECTED");
        names.put(FULL_STATE_SYNC, "FULL_STATE_SYNC"); names.put(PING, "PING");
        names.put(PONG, "PONG"); names.put(DISCONNECT, "DISCONNECT");
        names.put(RECONNECT, "RECONNECT"); names.put(NETWORK_LOST, "NETWORK_LOST");
        names.put(ERROR, "ERROR");
        NAMES = Collections.unmodifiableMap(names);
    }

    public static String nameOf(short type) { return NAMES.getOrDefault(type, "UNKNOWN"); }
    public static boolean isKnown(short type) { return NAMES.containsKey(type); }
    public static Map<Short, String> all() { return NAMES; }
}

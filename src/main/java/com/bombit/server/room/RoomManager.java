package com.bombit.server.room;

import com.bombit.server.network.OutboundMessage;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomManager {
    public static final short ROOM_STATE_TYPE = 0x0012;
    public static final short GAME_STARTED_TYPE = 0x0015;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final int maxPlayersPerRoom;
    private volatile RoomBroadcaster broadcaster = (roomId, message) -> { };

    public RoomManager(int maxPlayersPerRoom) {
        if (maxPlayersPerRoom < 2) {
            throw new IllegalArgumentException("maxPlayersPerRoom must be at least 2");
        }
        this.maxPlayersPerRoom = maxPlayersPerRoom;
    }

    public void setBroadcaster(RoomBroadcaster broadcaster) {
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
    }

    public Room createRoom(String roomName, Session host) {
        requireAvailable(host);
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, roomName, maxPlayersPerRoom, host);
        rooms.put(roomId, room);
        host.setRoomId(roomId);
        host.setState(SessionState.IN_ROOM);
        publishRoomState(room);
        return room;
    }

    public boolean joinRoom(String roomId, Session player) {
        requireAvailable(player);
        Room room = rooms.get(roomId);
        if (room == null) {
            return false;
        }
        synchronized (room) {
            if (rooms.get(roomId) != room || !room.addPlayer(player)) {
                return false;
            }
            player.setRoomId(roomId);
            player.setState(SessionState.IN_ROOM);
        }
        publishRoomState(room);
        return true;
    }

    public void leaveRoom(Session player) {
        String roomId = player.getRoomId();
        if (roomId == null) {
            return;
        }
        Room room = rooms.get(roomId);
        if (room != null) {
            synchronized (room) {
                room.removePlayer(player.getPlayerId());
                if (room.isEmpty()) {
                    rooms.remove(roomId, room);
                }
            }
            publishRoomState(room);
        }
        player.setRoomId(null);
        if (player.getState() != SessionState.CLOSED) {
            player.setState(SessionState.IN_LOBBY);
        }
    }

    public Optional<SessionState> toggleReady(Session player) {
        String roomId = player.getRoomId();
        if (roomId == null) {
            return Optional.empty();
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            return Optional.empty();
        }
        SessionState next;
        synchronized (room) {
            if (room.getState() != Room.State.WAITING) {
                return Optional.empty();
            }
            SessionState current = player.getState();
            if (current == SessionState.IN_ROOM) {
                next = SessionState.READY;
            } else if (current == SessionState.READY) {
                next = SessionState.IN_ROOM;
            } else {
                return Optional.empty();
            }
            player.setState(next);
        }
        publishRoomState(room);
        return Optional.of(next);
    }

    public Optional<Room> startGame(Session requester) {
        String roomId = requester.getRoomId();
        if (roomId == null) {
            return Optional.empty();
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            return Optional.empty();
        }
        synchronized (room) {
            if (!room.getHostPlayerId().equals(requester.getPlayerId()) || !room.canStartGame()) {
                return Optional.empty();
            }
            room.setState(Room.State.PLAYING);
            for (Session player : room.getPlayersSnapshot()) {
                player.setState(SessionState.PLAYING);
            }
        }
        broadcaster.broadcast(room.getRoomId(),
                new OutboundMessage(GAME_STARTED_TYPE, buildGameStartedPayload(room)));
        return Optional.of(room);
    }

    public Room find(String roomId) { return rooms.get(roomId); }
    public Map<String, Room> snapshot() { return Map.copyOf(rooms); }

    private void publishRoomState(Room room) {
        broadcaster.broadcast(room.getRoomId(),
                new OutboundMessage(ROOM_STATE_TYPE, buildRoomStatePayload(room)));
    }

    private static byte[] buildRoomStatePayload(Room room) {
        List<Session> players = room.getPlayersSnapshot();
        StringBuilder json = new StringBuilder("{");
        json.append("\"roomId\":\"").append(room.getRoomId()).append("\",");
        json.append("\"roomName\":\"").append(escapeJson(room.getRoomName())).append("\",");
        json.append("\"hostId\":\"").append(room.getHostPlayerId()).append("\",");
        json.append("\"state\":\"").append(room.getState()).append("\",");
        json.append("\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Session player = players.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"playerId\":\"").append(player.getPlayerId()).append("\",");
            json.append("\"username\":\"").append(escapeJson(player.getUsername())).append("\",");
            json.append("\"state\":\"").append(player.getState()).append("\"}");
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildGameStartedPayload(Room room) {
        List<Session> players = room.getPlayersSnapshot();
        StringBuilder json = new StringBuilder("{\"roomId\":\"");
        json.append(room.getRoomId()).append("\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Session player = players.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"playerId\":\"").append(player.getPlayerId()).append("\",");
            json.append("\"username\":\"").append(escapeJson(player.getUsername())).append("\"}");
        }
        json.append("]}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void requireAvailable(Session session) {
        if (session.getRoomId() != null) {
            throw new IllegalStateException("Session already belongs to a room");
        }
        if (session.getState() != SessionState.IN_LOBBY
                && session.getState() != SessionState.CONNECTED) {
            throw new IllegalStateException("Session cannot join a room from " + session.getState());
        }
    }
}

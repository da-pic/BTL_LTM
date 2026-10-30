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
        return createRoom(roomName, host, maxPlayersPerRoom);
    }

    public Room createRoom(String roomName, Session host, int capacity) {
        return createRoom(roomName,host,capacity,null);
    }
    public Room createRoom(String roomName,Session host,int capacity,com.bombit.common.dto.MatchConfiguration configuration){
        requireAvailable(host);
        if (capacity < 2 || capacity > 4) throw new IllegalArgumentException("Capacity must be 2..4");
        String roomId = UUID.randomUUID().toString();
        Room room = new Room(roomId, roomName, capacity, host,configuration);
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
        if (player.getState() != SessionState.CLOSED && player.getState() != SessionState.DISCONNECTED) {
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

    /** Protocol-2 READY sets a value; repeated requests must never toggle it back. */
    public Optional<SessionState> setReady(Session player, boolean ready) {
        String roomId = player.getRoomId();
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null) return Optional.empty();
        SessionState next = ready ? SessionState.READY : SessionState.IN_ROOM;
        synchronized (room) {
            if (rooms.get(roomId) != room || room.getState() != Room.State.WAITING
                    || !room.getPlayersSnapshot().contains(player)) return Optional.empty();
            SessionState current = player.getState();
            if (current != SessionState.IN_ROOM && current != SessionState.READY) return Optional.empty();
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

    public void publishRoomState(Room room) {
        if (!room.isEmpty()) broadcaster.broadcast(room.getRoomId(),
                OutboundMessage.fromPacket(room.configuration().isPresent()?com.bombit.common.protocol.MetadataV3Codec.roomState(viewV3(room)):
                        com.bombit.common.protocol.LobbyPacketCodec.roomState(view(room))));
    }
    public static com.bombit.common.dto.RoomStateView viewV3(Room room){
        synchronized(room){var config=room.configuration().orElseThrow();var members=new java.util.ArrayList<com.bombit.common.dto.RoomStateView.Member>();
            var players=room.getPlayersSnapshot();for(int i=0;i<players.size();i++){var p=players.get(i);int slot=room.slotOf(p.getPlayerId());members.add(new com.bombit.common.dto.RoomStateView.Member(p.getPlayerId(),p.getUsername(),p.getState().name(),slot,
                config.teamForSlot(slot),
                p.getState()==SessionState.READY?room.readyRevision(p.getPlayerId()):null));}
            return new com.bombit.common.dto.RoomStateView(room.getRoomId(),room.getRoomName(),room.getHostPlayerId(),room.getState().name(),room.getMaxPlayers(),room.getMapSelection(),room.configurationRevision(),config,members);
        }
    }

    public static com.bombit.common.protocol.LobbyPacketCodec.RoomView view(Room room) {
        synchronized (room) {
            return new com.bombit.common.protocol.LobbyPacketCodec.RoomView(room.getRoomId(), room.getRoomName(),
                    room.getHostPlayerId(), room.getState().name(), room.getMaxPlayers(), room.getMapSelection(),
                    room.getPlayersSnapshot().stream().map(p -> new com.bombit.common.protocol.LobbyPacketCodec.Player(
                            p.getPlayerId(), p.getUsername(), p.getState().name())).toList());
        }
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

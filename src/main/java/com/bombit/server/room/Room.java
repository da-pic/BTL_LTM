package com.bombit.server.room;

import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Room {
    public enum State { WAITING, PLAYING }

    private final String roomId;
    private final String roomName;
    private final int maxPlayers;
    private final List<Session> players = new ArrayList<>();
    private String hostPlayerId;
    private State state = State.WAITING;

    public Room(String roomId, String roomName, int maxPlayers, Session host) {
        this.roomId = Objects.requireNonNull(roomId, "roomId");
        this.roomName = Objects.requireNonNull(roomName, "roomName");
        if (maxPlayers < 2) {
            throw new IllegalArgumentException("A room needs at least two slots");
        }
        this.maxPlayers = maxPlayers;
        this.hostPlayerId = host.getPlayerId();
        players.add(host);
    }

    public synchronized boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public synchronized boolean hasPlayer(String playerId) {
        if (playerId == null) {
            return false;
        }
        return players.stream().anyMatch(p -> playerId.equals(p.getPlayerId()));
    }

    public synchronized boolean addPlayer(Session session) {
        if (session == null || state != State.WAITING || isFull() || hasPlayer(session.getPlayerId())) {
            return false;
        }
        players.add(session);
        return true;
    }

    public synchronized boolean removePlayer(String playerId) {
        if (playerId == null) {
            return false;
        }
        boolean removed = players.removeIf(player -> player.getPlayerId().equals(playerId));
        if (removed && playerId.equals(hostPlayerId) && !players.isEmpty()) {
            hostPlayerId = players.get(0).getPlayerId();
        }
        return removed;
    }

    public synchronized boolean canStartGame() {
        if (players.size() < 2 || state != State.WAITING) {
            return false;
        }
        return players.stream().allMatch(player ->
                player.getPlayerId().equals(hostPlayerId)
                        || player.getState() == SessionState.READY);
    }

    public synchronized List<Session> getPlayersSnapshot() {
        return List.copyOf(players);
    }

    public synchronized void setState(State state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public int getMaxPlayers() { return maxPlayers; }
    public synchronized String getHostPlayerId() { return hostPlayerId; }
    public synchronized State getState() { return state; }
    public synchronized boolean isEmpty() { return players.isEmpty(); }
}

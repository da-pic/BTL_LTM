package com.bombit.server.session;

import com.bombit.server.network.OutboundSender;

import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class Session {
    private final String playerId;
    private final String reconnectToken;
    private volatile String username;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.CONNECTED);
    private volatile Socket socket;
    private volatile String roomId;
    private volatile long lastHeartbeatNanos = System.nanoTime();
    private volatile OutboundSender outboundSender;
    private volatile SessionState stateBeforeDisconnect;

    public Session(String username, Socket socket) {
        this(UUID.randomUUID().toString(), UUID.randomUUID().toString(), username, socket);
    }

    public Session(String playerId, String reconnectToken, String username, Socket socket) {
        this.playerId = requireText(playerId, "playerId");
        this.reconnectToken = requireText(reconnectToken, "reconnectToken");
        this.username = requireText(username, "username");
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    public String getPlayerId() { return playerId; }
    public String getReconnectToken() { return reconnectToken; }
    public String getUsername() { return username; }
    public Socket getSocket() { return socket; }
    public String getRoomId() { return roomId; }
    public SessionState getState() { return state.get(); }
    public long getLastHeartbeatNanos() { return lastHeartbeatNanos; }
    public OutboundSender getOutboundSender() { return outboundSender; }

    /** A provisional connection becomes a lobby session exactly once after validated CONNECT. */
    public synchronized void completeHandshake(String name) {
        if (state.get() != SessionState.CONNECTED) throw new IllegalStateException("Already connected");
        username = requireText(name, "username");
        updateHeartbeat();
        state.set(SessionState.IN_LOBBY);
    }

    public void setRoomId(String roomId) { this.roomId = roomId; }
    public void setOutboundSender(OutboundSender sender) { this.outboundSender = sender; }

    public boolean transition(SessionState expected, SessionState next) {
        if (!state.compareAndSet(expected, next)) {
            return false;
        }
        if (next == SessionState.DISCONNECTED) {
            stateBeforeDisconnect = expected;
        }
        return true;
    }

    public SessionState restoreStateAfterReconnect(SessionState fallback) {
        SessionState previous = stateBeforeDisconnect;
        stateBeforeDisconnect = null;
        SessionState restored = previous != null ? previous : fallback;
        state.set(restored);
        return restored;
    }

    public void setState(SessionState next) {
        state.set(Objects.requireNonNull(next, "next"));
    }

    public void updateHeartbeat() {
        lastHeartbeatNanos = System.nanoTime();
    }

    public boolean isHeartbeatExpired(long timeoutNanos) {
        return System.nanoTime() - lastHeartbeatNanos >= timeoutNanos;
    }

    public synchronized void attachSocket(Socket newSocket) {
        Objects.requireNonNull(newSocket, "newSocket");
        closeSocket();
        socket = newSocket;
        updateHeartbeat();
    }

    public synchronized void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (java.io.IOException ignored) {
                // Cleanup is best effort; the session is still removed by its owner.
            }
        }
    }

    /** Releases ownership without closing: used when a RECONNECT frame moves this socket to another session. */
    public synchronized Socket detachSocket() {
        Socket current = socket;
        socket = null;
        return current;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

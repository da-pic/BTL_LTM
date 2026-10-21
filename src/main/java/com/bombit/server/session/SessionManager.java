package com.bombit.server.session;

import com.bombit.server.integration.SessionLifecycleListener;
import com.bombit.server.network.BroadcastSender;
import com.bombit.server.network.ClientSender;
import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import com.bombit.server.room.RoomBroadcaster;

import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SessionManager implements ClientSender, RoomBroadcaster, AutoCloseable {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final SessionLifecycleListener lifecycleListener;
    private final long reconnectGraceSeconds;
    private final OutboundSender sender = new BroadcastSender();

    public SessionManager(SessionLifecycleListener lifecycleListener, long reconnectGraceSeconds) {
        if (reconnectGraceSeconds <= 0) {
            throw new IllegalArgumentException("reconnectGraceSeconds must be positive");
        }
        this.lifecycleListener = lifecycleListener;
        this.reconnectGraceSeconds = reconnectGraceSeconds;
    }

    public void register(Session session) {
        Objects.requireNonNull(session, "session");
        if (sessions.putIfAbsent(session.getPlayerId(), session) != null) {
            throw new IllegalStateException("Session already exists: " + session.getPlayerId());
        }
    }

    public Optional<Session> find(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(playerId));
    }

    public Map<String, Session> snapshot() {
        return Map.copyOf(sessions);
    }

    public void disconnect(String playerId) {
        handleGracefulDisconnect(playerId);
    }

    public void handleUnexpectedDisconnect(String playerId) {
        if (playerId == null) {
            return;
        }
        Session session = sessions.get(playerId);
        if (session == null || !markDisconnected(session)) {
            return;
        }
        session.closeSocket();
        lifecycleListener.onPlayerDisconnected(session);
        ScheduledFuture<?> timer = scheduler.schedule(
                () -> hardDrop(playerId),
                reconnectGraceSeconds,
                TimeUnit.SECONDS);
        ScheduledFuture<?> previous = disconnectTimers.put(playerId, timer);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void handleGracefulDisconnect(String playerId) {
        if (playerId == null) {
            return;
        }
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        cancelDisconnectTimer(playerId);
        session.setState(SessionState.CLOSED);
        session.closeSocket();
        lifecycleListener.onPlayerHardDropped(session);
    }

    public boolean reconnect(String playerId, String token, Socket newSocket) {
        if (playerId == null || token == null) {
            closeQuietly(newSocket);
            return false;
        }
        Session session = sessions.get(playerId);
        if (session == null || !token.equals(session.getReconnectToken())
                || session.getState() != SessionState.DISCONNECTED) {
            closeQuietly(newSocket);
            return false;
        }
        synchronized (session) {
            if (session.getState() != SessionState.DISCONNECTED) {
                closeQuietly(newSocket);
                return false;
            }
            cancelDisconnectTimer(playerId);
            session.attachSocket(newSocket);
            session.restoreStateAfterReconnect(SessionState.PLAYING);
        }
        lifecycleListener.onPlayerReconnected(session);
        return true;
    }

    public void hardDrop(String playerId) {
        if (playerId == null) {
            return;
        }
        Session session = sessions.remove(playerId);
        cancelDisconnectTimer(playerId);
        if (session == null) {
            return;
        }
        session.setState(SessionState.CLOSED);
        session.closeSocket();
        lifecycleListener.onPlayerHardDropped(session);
    }

    @Override
    public void sendToPlayer(String playerId, OutboundMessage message) {
        if (playerId == null || message == null) {
            return;
        }
        Session session = sessions.get(playerId);
        if (session != null && session.getState() != SessionState.DISCONNECTED
                && session.getState() != SessionState.CLOSED) {
            sender.send(session, message);
        }
    }

    @Override
    public void broadcast(String roomId, OutboundMessage message) {
        broadcastExcept(roomId, null, message);
    }

    @Override
    public void broadcastExcept(String roomId, String excludedPlayerId, OutboundMessage message) {
        if (roomId == null || message == null) {
            return;
        }
        for (Session session : sessions.values()) {
            if (excludedPlayerId != null && excludedPlayerId.equals(session.getPlayerId())) {
                continue;
            }
            SessionState state = session.getState();
            if (roomId.equals(session.getRoomId())
                    && state != SessionState.DISCONNECTED
                    && state != SessionState.CLOSED) {
                sender.send(session, message);
            }
        }
    }

    public void broadcastToRoom(String roomId, OutboundMessage message) {
        broadcast(roomId, message);
    }

    public int getOnlineCount() {
        int count = 0;
        for (Session session : sessions.values()) {
            SessionState state = session.getState();
            if (state != SessionState.DISCONNECTED && state != SessionState.CLOSED) {
                count++;
            }
        }
        return count;
    }

    private boolean markDisconnected(Session session) {
        SessionState current = session.getState();
        while (current != SessionState.DISCONNECTED && current != SessionState.CLOSED) {
            if (session.transition(current, SessionState.DISCONNECTED)) {
                return true;
            }
            current = session.getState();
        }
        return false;
    }

    private void cancelDisconnectTimer(String playerId) {
        ScheduledFuture<?> timer = disconnectTimers.remove(playerId);
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (java.io.IOException ignored) {
            // The rejected socket is no longer owned by the server.
        }
    }

    @Override
    public void close() {
        for (String playerId : sessions.keySet()) {
            hardDrop(playerId);
        }
        scheduler.shutdownNow();
    }
}

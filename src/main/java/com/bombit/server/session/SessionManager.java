package com.bombit.server.session;

import com.bombit.server.integration.SessionLifecycleListener;
import com.bombit.server.network.BroadcastSender;
import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;

import java.net.Socket;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SessionManager implements AutoCloseable {
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
        if (sessions.putIfAbsent(session.getPlayerId(), session) != null) {
            throw new IllegalStateException("Session already exists: " + session.getPlayerId());
        }
    }

    public Optional<Session> find(String playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public Map<String, Session> snapshot() {
        return Map.copyOf(sessions);
    }

    public void handleUnexpectedDisconnect(String playerId) {
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
        Session session = sessions.get(playerId);
        if (session == null || token == null || !token.equals(session.getReconnectToken())
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
        Session session = sessions.remove(playerId);
        cancelDisconnectTimer(playerId);
        if (session == null) {
            return;
        }
        session.setState(SessionState.CLOSED);
        session.closeSocket();
        lifecycleListener.onPlayerHardDropped(session);
    }

    public void broadcastToRoom(String roomId, OutboundMessage message) {
        if (roomId == null || message == null) {
            return;
        }
        for (Session session : sessions.values()) {
            SessionState state = session.getState();
            if (roomId.equals(session.getRoomId())
                    && state != SessionState.DISCONNECTED
                    && state != SessionState.CLOSED) {
                sender.send(session, message);
            }
        }
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

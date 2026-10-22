package com.bombit.server.session;

import com.bombit.server.integration.GameEventSink;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledThreadPoolExecutor;

public final class SessionManager implements AutoCloseable {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2,r->new Thread(r,"session-expiry"));
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final GameEventSink gameEventSink;
    private final long reconnectGraceSeconds;
    private final OutboundSender sender = new BroadcastSender();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SessionManager(GameEventSink gameEventSink, long reconnectGraceSeconds) {
        if (reconnectGraceSeconds <= 0) {
            throw new IllegalArgumentException("reconnectGraceSeconds must be positive");
        }
        this.gameEventSink = gameEventSink;
        this.reconnectGraceSeconds = reconnectGraceSeconds;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    public synchronized void register(Session session) {
        if (closed.get()) throw new IllegalStateException("Session manager closed");
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
        if (closed.get()) { hardDrop(playerId); return; }
        Session session = sessions.get(playerId);
        if (session == null || !markDisconnected(session)) {
            return;
        }
        session.closeSocket();
        notifySafely(()->gameEventSink.onPlayerDisconnected(session));
        ScheduledFuture<?> timer;
        try {
            timer = scheduler.schedule(() -> hardDrop(playerId), reconnectGraceSeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            hardDrop(playerId);
            return;
        }
        ScheduledFuture<?> previous = disconnectTimers.put(playerId, timer);
        if (previous != null) {
            previous.cancel(false);
        }
        if (closed.get() || session.getState() != SessionState.DISCONNECTED) cancelDisconnectTimer(playerId);
    }

    public void handleGracefulDisconnect(String playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        cancelDisconnectTimer(playerId);
        session.setState(SessionState.CLOSED);
        session.closeSocket();
        notifySafely(()->gameEventSink.onPlayerHardDropped(session));
    }

    public boolean reconnect(String playerId, String token, Socket newSocket) {
        if (closed.get()) { closeQuietly(newSocket); return false; }
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
        notifySafely(()->gameEventSink.onPlayerReconnected(session));
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
        notifySafely(()->gameEventSink.onPlayerHardDropped(session));
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
                try{sender.send(session,message);}catch(RuntimeException e){failure.compareAndSet(null,e);}
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
    private void notifySafely(Runnable callback){try{callback.run();}catch(RuntimeException e){failure.compareAndSet(null,e);}}
    public Optional<Throwable> failure(){return Optional.ofNullable(failure.get());}
    public int pendingDisconnectTimers(){return disconnectTimers.size();}
    public boolean isTerminated(){return scheduler.isTerminated();}

    @Override
    public void close() {
        synchronized(this){if (!closed.compareAndSet(false, true)) return;}
        for (String playerId : sessions.keySet()) {
            hardDrop(playerId);
        }
        scheduler.shutdownNow();
        disconnectTimers.clear();
        try{scheduler.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}

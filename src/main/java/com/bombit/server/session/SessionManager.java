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
import java.util.function.Consumer;

public final class SessionManager implements AutoCloseable {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
    private final Map<String, Object> disconnectEpochs = new ConcurrentHashMap<>();
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
        disconnect(playerId, null, false);
    }

    /** Ignore a reader/writer failure from a connection that has already been replaced. */
    public void handleUnexpectedDisconnect(String playerId, Socket expectedSocket) {
        disconnect(playerId, expectedSocket, true);
    }

    private void disconnect(String playerId, Socket expectedSocket, boolean checkSocket) {
        if (closed.get()) { hardDrop(playerId); return; }
        Session session = sessions.get(playerId);
        Object epoch = new Object();
        if (session == null) return;
        synchronized (session) {
            if (checkSocket && session.getSocket() != expectedSocket) return;
            if (!markDisconnected(session)) return;
            disconnectEpochs.put(playerId, epoch);
            session.closeSocket();
        }
        notifySafely(()->gameEventSink.onPlayerDisconnected(session));
        ScheduledFuture<?> timer;
        try {
            timer = scheduler.schedule(() -> expire(playerId, session, epoch), reconnectGraceSeconds, TimeUnit.SECONDS);
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
        return reconnect(playerId, token, newSocket, resumed -> { });
    }

    /** True when a RECONNECT frame would be accepted; used before detaching the provisional connection. */
    public boolean canReconnect(String playerId, String token) {
        Session session = playerId == null ? null : sessions.get(playerId);
        if (closed.get() || session == null || token == null || !token.equals(session.getReconnectToken())) return false;
        SessionState state = session.getState();
        return state != SessionState.CONNECTED && state != SessionState.CLOSED;
    }

    /**
     * Seamless Session Recovery: attach a new socket to the soft-dropped session.
     * A valid token also takes over a connection the server still believes is alive
     * (half-open TCP after Wi-Fi loss is detected by the client before the server).
     * {@code beforeResume} runs before game observers so the new writer exists when they send.
     */
    public boolean reconnect(String playerId, String token, Socket newSocket, Consumer<Session> beforeResume) {
        if (!canReconnect(playerId, token)) { closeQuietly(newSocket); return false; }
        Session session = sessions.get(playerId);
        if (session == null) { closeQuietly(newSocket); return false; }
        if (session.getState() != SessionState.DISCONNECTED) handleUnexpectedDisconnect(playerId);
        synchronized (session) {
            if (session.getState() != SessionState.DISCONNECTED || sessions.get(playerId) != session) {
                closeQuietly(newSocket);
                return false;
            }
            cancelDisconnectTimer(playerId);
            session.attachSocket(newSocket);
            session.restoreStateAfterReconnect(SessionState.PLAYING);
        }
        try { beforeResume.accept(session); }
        catch (RuntimeException failed) { failure.compareAndSet(null, failed); hardDrop(playerId); return false; }
        notifySafely(()->gameEventSink.onPlayerReconnected(session));
        return true;
    }

    /**
     * Removes a provisional (pre-handshake) entry without callbacks and without closing its socket,
     * because that socket is being handed to the session named in a RECONNECT frame.
     */
    public boolean discardProvisional(String provisionalId) {
        Session session = sessions.get(provisionalId);
        if (session == null) return false;
        synchronized (session) {
            if (session.getState() != SessionState.CONNECTED || !sessions.remove(provisionalId, session)) return false;
            session.detachSocket();
            session.setState(SessionState.CLOSED);
        }
        return true;
    }

    public void hardDrop(String playerId) {
        Session session = sessions.get(playerId);
        if (session == null) {
            cancelDisconnectTimer(playerId);
            return;
        }
        synchronized (session) {
            if (!sessions.remove(playerId, session)) return;
            cancelDisconnectTimer(playerId);
            session.setState(SessionState.CLOSED);
            session.closeSocket();
        }
        notifySafely(()->gameEventSink.onPlayerHardDropped(session));
    }

    /** Grace timer: a late timer must never drop a session that already resumed on a new socket. */
    private void expire(String playerId, Session expected, Object epoch) {
        synchronized (expected) {
            if (sessions.get(playerId) != expected || expected.getState() != SessionState.DISCONNECTED
                    || disconnectEpochs.get(playerId) != epoch) return;
            if (!sessions.remove(playerId, expected)) return;
            cancelDisconnectTimer(playerId);
            expected.setState(SessionState.CLOSED);
            expected.closeSocket();
        }
        notifySafely(() -> gameEventSink.onPlayerHardDropped(expected));
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
        disconnectEpochs.remove(playerId);
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

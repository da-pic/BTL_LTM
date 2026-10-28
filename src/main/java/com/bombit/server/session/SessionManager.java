package com.bombit.server.session;

import com.bombit.server.integration.GameEventSink;
import com.bombit.server.network.BroadcastSender;
import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Socket và cửa sổ grace được thay đổi nguyên tử dưới khóa session.
 * Callback sang lobby luôn chạy ngoài khóa để tránh khóa chéo session -> lobby.
 */
public final class SessionManager implements AutoCloseable {
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    // Mỗi lần mất mạng có ticket riêng; timer lần trước không được xoá phiên của lần sau.
    private static final class DisconnectWindow {
        final long started;
        volatile ScheduledFuture<?> timer;
        DisconnectWindow(long started) { this.started = started; }
    }
    private final Map<String, DisconnectWindow> disconnectTimers = new ConcurrentHashMap<>();
    private final Set<Session> recovering = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final GameEventSink gameEventSink;
    private final long graceNanos;
    private final LongSupplier nanoClock;
    private final OutboundSender sender = new BroadcastSender();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SessionManager(GameEventSink sink, long graceSeconds) {
        this(sink, graceSeconds, System::nanoTime,
                new ScheduledThreadPoolExecutor(2, r -> new Thread(r, "session-expiry")));
    }
    // Tiêm clock/scheduler để kiểm thử deadline/race mà không sleep 15 giây.
    SessionManager(GameEventSink sink, long graceSeconds, LongSupplier clock, ScheduledThreadPoolExecutor scheduler) {
        if (graceSeconds <= 0) throw new IllegalArgumentException("reconnectGraceSeconds must be positive");
        gameEventSink = Objects.requireNonNull(sink);
        graceNanos = TimeUnit.SECONDS.toNanos(graceSeconds);
        nanoClock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        scheduler.setRemoveOnCancelPolicy(true);
    }
    public synchronized void register(Session session) {
        if (closed.get()) throw new IllegalStateException("Session manager closed");
        if (sessions.putIfAbsent(session.getPlayerId(), session) != null)
            throw new IllegalStateException("Session already exists: " + session.getPlayerId());
    }
    public Optional<Session> find(String id) { return Optional.ofNullable(sessions.get(id)); }
    public Map<String, Session> snapshot() { return Map.copyOf(sessions); }

    public void handleUnexpectedDisconnect(String id) { disconnect(id, null, false, 0); }
    /** Reader/writer cũ chỉ được ngắt socket mà nó sở hữu, không ngắt kết nối mới. */
    public void handleUnexpectedDisconnect(String id, Socket expectedSocket) { disconnect(id, expectedSocket, true, 0); }
    /** Kiểm tra lại heartbeat trong khóa: PING vừa tới không bị sweep dựa trên dữ liệu cũ đóng phiên. */
    public void handleHeartbeatTimeout(String id, Socket expectedSocket, long timeoutNanos) {
        if(timeoutNanos<=0)throw new IllegalArgumentException("Invalid heartbeat timeout");
        disconnect(id,expectedSocket,true,timeoutNanos);
    }
    private void disconnect(String id, Socket expectedSocket, boolean checkSocket, long heartbeatTimeout) {
        Session session = sessions.get(id);
        if (session == null) return;
        DisconnectWindow window;
        synchronized (session) {
            if (sessions.get(id) != session || (checkSocket && session.getSocket() != expectedSocket)) return;
            if (session.getState() == SessionState.DISCONNECTED || session.getState() == SessionState.CLOSED) return;
            if(heartbeatTimeout>0 && !session.isHeartbeatExpired(heartbeatTimeout))return;
            if(heartbeatTimeout>0 && session.getState()==SessionState.CONNECTED) {
                sessions.remove(id,session);session.setState(SessionState.CLOSED);session.closeSocket();
                window=null;
            } else {
            session.transition(session.getState(), SessionState.DISCONNECTED);
            session.closeSocket();
            window = new DisconnectWindow(nanoClock.getAsLong());
            cancelDisconnectTimer(id);
            disconnectTimers.put(id, window);
            }
        }
        if(window==null){notifySafely(()->gameEventSink.onPlayerHardDropped(session));return;}
        notifySafely(() -> gameEventSink.onPlayerDisconnected(session));
        synchronized (session) {
            // Callback có thể shutdown/reconnect; chỉ lên lịch nếu ticket còn hiệu lực.
            if (sessions.get(id) != session || disconnectTimers.get(id) != window) return;
            if (!closed.get()) {
                try {
                    long remaining = Math.max(0, graceNanos - (nanoClock.getAsLong() - window.started));
                    window.timer = scheduler.schedule(() -> expire(id, session, window), remaining, TimeUnit.NANOSECONDS);
                    return;
                } catch (RejectedExecutionException shuttingDown) { /* dọn ngoài khóa */ }
            }
        }
        hardDrop(id);
    }
    public void handleGracefulDisconnect(String id) { hardDrop(id); }
    public boolean canReconnect(String id, String token) {
        Session session = id == null ? null : sessions.get(id);
        if (session == null) return false;
        synchronized (session) { return eligible(id, token, session); }
    }
    private boolean eligible(String id, String token, Session session) {
        if (closed.get() || sessions.get(id) != session || recovering.contains(session)
                || token == null || !token.equals(session.getReconnectToken())) return false;
        SessionState state = session.getState();
        if (state == SessionState.CONNECTED || state == SessionState.CLOSED) return false;
        DisconnectWindow window = disconnectTimers.get(id);
        return state != SessionState.DISCONNECTED || (window != null && !expired(window));
    }
    private boolean expired(DisconnectWindow window) { return nanoClock.getAsLong() - window.started >= graceNanos; }
    public boolean reconnect(String id, String token, Socket socket) { return reconnect(id, token, socket, s -> { }); }

    /** Giữ một quyền phục hồi trong suốt attach/writer/ACK/callback; từ chối request chồng nhau.
     * Token hợp lệ vẫn cho phép takeover TCP half-open mà chưa cần heartbeat phát hiện trước.
     */
    public boolean reconnect(String id, String token, Socket socket, Consumer<Session> beforeResume) {
        Objects.requireNonNull(socket);
        Session session = id == null ? null : sessions.get(id);
        if (session == null) { closeQuietly(socket); return false; }
        synchronized (session) {
            if (!eligible(id, token, session)) { closeQuietly(socket); return false; }
            recovering.add(session);
        }
        try {
            if (session.getState() != SessionState.DISCONNECTED) handleUnexpectedDisconnect(id);
            synchronized (session) {
                DisconnectWindow window = disconnectTimers.get(id);
                if (closed.get() || sessions.get(id) != session || session.getState() != SessionState.DISCONNECTED
                        || window == null || expired(window)) { closeQuietly(socket); return false; }
                cancelDisconnectTimer(id);
                session.attachSocket(socket);
                // Giữ DISCONNECTED khi dựng writer: pump/heartbeat chưa dùng socket mới.
            }
            try {
                beforeResume.accept(session);
                synchronized (session) {
                    if (closed.get() || sessions.get(id) != session || session.getSocket() != socket) {
                        closeQuietly(socket); return false;
                    }
                    session.restoreStateAfterReconnect(SessionState.PLAYING);
                }
                gameEventSink.onPlayerReconnected(session);
            } catch (SessionRecoveryRejectedException rejected) {
                // Match có thể đã kết thúc trong grace. Đây là kết quả từ chối hợp lệ;
                // vẫn dọn socket/writer/timer, không ghi như crash/hỏng callback.
                hardDrop(id, socket);
                return false;
            } catch (RuntimeException failed) {
                failure.compareAndSet(null, failed);
                hardDrop(id, socket);
                return false;
            }
            return sessions.get(id) == session && session.getSocket() == socket && !socket.isClosed();
        } finally { recovering.remove(session); }
    }
    /** Bỏ session tạm sau xác thực; giữ socket sống để chuyển cho identity cũ. */
    public boolean discardProvisional(String id) {
        Session session = sessions.get(id);
        if (session == null) return false;
        synchronized (session) {
            if (session.getState() != SessionState.CONNECTED || !sessions.remove(id, session)) return false;
            session.detachSocket(); session.setState(SessionState.CLOSED); return true;
        }
    }
    public void hardDrop(String id) { drop(id, null, false); }
    public void hardDrop(String id, Socket expectedSocket) { drop(id, expectedSocket, true); }
    private void drop(String id, Socket expectedSocket, boolean checkSocket) {
        Session session = sessions.get(id);
        if (session == null) return;
        synchronized (session) {
            if ((checkSocket && session.getSocket() != expectedSocket) || !sessions.remove(id, session)) return;
            cancelDisconnectTimer(id); session.setState(SessionState.CLOSED); session.closeSocket();
        }
        notifySafely(() -> gameEventSink.onPlayerHardDropped(session));
    }
    private void expire(String id, Session session, DisconnectWindow window) {
        synchronized (session) {
            // Kiểm tra ticket và xoá TRONG CÙNG khóa: loại khe hở check-then-hardDrop.
            if (sessions.get(id) != session || disconnectTimers.get(id) != window
                    || session.getState() != SessionState.DISCONNECTED || !expired(window)) return;
            if (!sessions.remove(id, session)) return;
            cancelDisconnectTimer(id); session.setState(SessionState.CLOSED); session.closeSocket();
        }
        notifySafely(() -> gameEventSink.onPlayerHardDropped(session));
    }
    private void cancelDisconnectTimer(String id) {
        DisconnectWindow window = disconnectTimers.remove(id);
        if (window != null && window.timer != null) window.timer.cancel(false);
    }
    public void broadcastToRoom(String roomId, OutboundMessage message) {
        if (roomId == null || message == null) return;
        for (Session session : sessions.values()) {
            SessionState state = session.getState();
            if (roomId.equals(session.getRoomId()) && state != SessionState.DISCONNECTED && state != SessionState.CLOSED) {
                try { sender.send(session, message); } catch (RuntimeException e) { failure.compareAndSet(null, e); }
            }
        }
    }
    public int getOnlineCount() {
        return (int) sessions.values().stream().filter(s -> s.getState() != SessionState.DISCONNECTED
                && s.getState() != SessionState.CLOSED).count();
    }
    private static void closeQuietly(Socket socket) { try { socket.close(); } catch (java.io.IOException ignored) { } }
    private void notifySafely(Runnable callback) { try { callback.run(); } catch (RuntimeException e) { failure.compareAndSet(null, e); } }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure.get()); }
    public int pendingDisconnectTimers() { return disconnectTimers.size(); }
    public boolean isTerminated() { return scheduler.isTerminated(); }
    @Override public void close() {
        synchronized (this) { if (!closed.compareAndSet(false, true)) return; }
        for (String id : sessions.keySet()) hardDrop(id);
        scheduler.shutdownNow(); disconnectTimers.clear();
        try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

package com.bombit.client.app;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Permanent listeners precede CONNECT; metadata/full/delta are dispatched in order to the UI. */
public final class DemoClientConnection implements AutoCloseable {
    public enum RecoveryState { CONNECTING, CONNECTED, RECONNECTING, WAITING_ACK, WAITING_FULL, CLOSED }
    /** Deadline riêng từng pha và toàn đợt. Constructor package-private giúp test server im lặng nhanh. */
    record RecoveryTimeouts(long totalMillis,long ackMillis,long fullMillis,long retryMillis,int connectMillis) {
        RecoveryTimeouts {
            if(totalMillis<=0||ackMillis<=0||fullMillis<=0||retryMillis<=0||connectMillis<=0)
                throw new IllegalArgumentException("Invalid recovery timeouts");
        }
    }
    private static final class Recovery {
        final com.bombit.common.dto.MatchContext match;
        final long deadline;
        long epoch,phaseDeadline;
        int attempts;
        boolean metadata;
        Recovery(com.bombit.common.dto.MatchContext match,long deadline,long epoch) {
            this.match=match;this.deadline=deadline;this.epoch=epoch;
        }
    }
    private final ClientNetwork network = new ClientNetwork();
    private final Executor ui;
    private final Consumer<MovementClientController> started;
    private final Consumer<String> status;
    private final Consumer<Throwable> errors;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Demo-Client-Control"); thread.setDaemon(true); return thread;
    });
    // Watchdog độc lập: connect/send đang block trên control không làm mất deadline ACK/full.
    private final ScheduledExecutorService recoveryWatchdog=Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"Client-Recovery-Watchdog");thread.setDaemon(true);return thread;
    });
    private final Object recoveryLock=new Object();
    private final java.util.concurrent.atomic.AtomicLong wireEpoch=new java.util.concurrent.atomic.AtomicLong();
    private final RecoveryTimeouts timeouts;
    private volatile Recovery recovery;
    private volatile RecoveryState recoveryState=RecoveryState.CONNECTING;
    private Consumer<RecoveryState> recoveryListener=state->{};
    private final Semaphore uiSlots = new Semaphore(256);
    private final Semaphore controlSlots=new Semaphore(128);
    private final java.util.concurrent.atomic.AtomicLong uiBytes=new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(), resyncQueued = new AtomicBoolean(), reconnecting = new AtomicBoolean();
    private volatile boolean autoReconnect = false;
    private volatile long receivedNanos = System.nanoTime();
    private volatile long lastResync;
    private int pingId;
    private volatile String sessionId;
    private volatile String reconnectToken;
    private volatile String host;
    private volatile int port;
    private volatile MatchContext context;
    private Consumer<Packet> lobby;
    private volatile MovementClientController movement;
    private com.bombit.common.dto.MatchResult lastResult;
    public com.bombit.common.dto.MatchResult lastResult(){return lastResult;}

    public DemoClientConnection(Executor ui, Consumer<MovementClientController> started,
                                Consumer<String> status, Consumer<Throwable> errors) {
        this(ui,started,status,errors,new RecoveryTimeouts(14_000,2_500,4_000,200,1_500));
    }
    DemoClientConnection(Executor ui, Consumer<MovementClientController> started,
                         Consumer<String> status, Consumer<Throwable> errors,RecoveryTimeouts timeouts) {
        this.ui=ui; this.started=started; this.status=status; this.errors=errors;
        this.timeouts=timeouts;
        network.setPacketQueueEnabled(false);
        network.addPacketListener(packet -> {
            receivedNanos = System.nanoTime();
            long epoch=wireEpoch.get();
            // Packet đã xếp lên UI của socket trước không được áp sau khi đổi socket.
            dispatch(() -> {if(epoch==wireEpoch.get())packet(packet);},6+packet.getJsonPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        });
        network.setOnDisconnect(this::connectionLost);
        recoveryWatchdog.scheduleAtFixedRate(this::checkRecoveryTimeouts,50,50,TimeUnit.MILLISECONDS);
    }
    /** Lobby events and command errors remain recoverable on the UI executor. */
    public void setLobbyListener(Consumer<Packet> listener) { lobby=listener; }
    public String sessionId() { return sessionId; }
    public String reconnectToken() { return reconnectToken; }
    public boolean isReconnecting() { return reconnecting.get(); }
    public RecoveryState recoveryState() { return recoveryState; }
    /** Callback được phát trên UI executor, không cập nhật Swing từ reader/control. */
    public void setRecoveryStateListener(Consumer<RecoveryState> listener) { recoveryListener=java.util.Objects.requireNonNull(listener); }
    public void setAutoReconnect(boolean enabled) { this.autoReconnect = enabled; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public void send(Packet packet) {
        long epoch=wireEpoch.get();
        // Không giữ lệnh chat/room/end từ connection cũ để phát lại sau khi phục hồi.
        if(closed.get()||reconnecting.get())return;
        if(!controlSlots.tryAcquire()){fail(new IOException("Client control queue full"));return;}
        try { control.execute(() -> {
            try {
                if(!closed.get()) {
                    if (reconnecting.get()||wireEpoch.get()!=epoch) return;
                    network.send(packet);
                }
            } catch(Exception e) {
                if (autoReconnect && context != null && !closed.get() && sessionId != null && reconnectToken != null) {
                    connectionLost(e);
                } else {
                    fail(e);
                }
            } finally{controlSlots.release();}
        }); }
        catch(RejectedExecutionException stopping) {controlSlots.release();}
    }
    public void connect(String host, int port, String name) throws IOException {
        this.host = host;
        this.port = port;
        try {
            Packet connect = SessionPacketCodec.connectRequest(name);
            SessionPacketCodec.connect(connect);
            wireEpoch.incrementAndGet();receivedNanos=System.nanoTime();
            network.connect(host, port);
            network.send(connect);
            control.scheduleAtFixedRate(() -> {
                if (closed.get() || reconnecting.get()) return;
                try {
                    if (System.nanoTime() - receivedNanos > TimeUnit.SECONDS.toNanos(10)) throw new IOException("Server heartbeat timeout");
                    network.send(new Packet(PacketType.PING, "{\"pingId\":" + ++pingId + "}"));
                } catch (Exception error) {
                    if (autoReconnect && context != null && !closed.get() && sessionId != null && reconnectToken != null) {
                        connectionLost(error);
                    } else {
                        fail(error);
                    }
                }
            }, 2, 2, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException error) { fail(error); throw error; }
    }
    private void dispatch(Runnable action) {
        dispatch(action,0);
    }
    private void dispatch(Runnable action,int bytes) {
        if (closed.get()) return;
        if (!uiSlots.tryAcquire()) { fail(new IOException("Client display queue is full")); return; }
        if(uiBytes.addAndGet(bytes)>4L*1024*1024){uiBytes.addAndGet(-bytes);uiSlots.release();fail(new IOException("Client display byte budget is full"));return;}
        try {
            ui.execute(() -> {
                try { if (!closed.get()) action.run(); }
                catch (RuntimeException error) { fail(error); }
                finally { uiBytes.addAndGet(-bytes);uiSlots.release(); }
            });
        } catch (RuntimeException error) { uiBytes.addAndGet(-bytes);uiSlots.release(); fail(error); }
    }
    private void packet(Packet packet) {
        switch (packet.getType()) {
            case PacketType.CONNECT_ACK -> {
                var ack = SessionPacketCodec.decodeAcknowledgement(packet);
                Recovery r=recovery;
                if(r!=null) {
                    synchronized(recoveryLock) {
                        if(recovery!=r||recoveryState!=RecoveryState.WAITING_ACK)return;
                        if(!ack.playerId().equals(sessionId))throw new IllegalStateException("Recovery identity mismatch");
                        reconnectToken=ack.reconnectToken();
                        recoveryState=RecoveryState.WAITING_FULL;
                        r.phaseDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeouts.fullMillis());
                    }
                    publishRecoveryStatus();return;
                }
                sessionId = ack.playerId();
                reconnectToken = ack.reconnectToken();
                recoveryState=RecoveryState.CONNECTED;recoveryListener.accept(recoveryState);
                status.accept("Connected. Waiting for the other players...");
            }
            case PacketType.ROOM_STATE, PacketType.LIST_ROOMS, PacketType.LEAVE_ROOM, PacketType.CHAT -> {
                if(lobby!=null) lobby.accept(packet); else status.accept("Waiting for all demo players to connect...");
            }
            case PacketType.GAME_OVER -> {
                if(context==null) return;
                Packet body;
                try { body=MatchEnvelopeCodec.unwrap(packet,context.matchId()); } catch(IOException stale) { return; }
                lastResult=GameplayPacketCodec.decodeResult(body);
                synchronized(recoveryLock){recovery=null;reconnecting.set(false);recoveryState=RecoveryState.CONNECTED;}
                if(movement!=null) movement.close(); movement=null; context=null;
                recoveryListener.accept(recoveryState);
                if(lobby!=null) lobby.accept(packet);
            }
            case PacketType.GAME_STARTED -> {
                if (sessionId == null) throw new IllegalStateException("Game started before handshake");
                MatchContext next = DemoPacketCodec.decodeStarted(packet);
                Recovery r=recovery;
                if(r!=null) {
                    synchronized(recoveryLock) {
                        if(recovery!=r||recoveryState!=RecoveryState.WAITING_FULL)return;
                        if(!r.match.equals(next))throw new IllegalStateException("Recovery match metadata mismatch");
                        r.metadata=true;
                    }
                    // Giữ controller/cửa sổ và counter plant; server đã chủ động yêu cầu full.
                    return;
                }
                if (context != null && context.matchId().equals(next.matchId())) {
                    if (movement == null || movement.isClosed()) {
                        int localId = context.playerId(sessionId).orElseThrow();
                        movement = new MovementClientController(network, localId, ui, () -> dispatch(this::requestFullState),
                                error->{if(context!=null&&context.matchId().equals(next.matchId()))fail(error);}, context.matchId(), false);
                        movement.setTransportErrorHandler(this::connectionLost);
                        started.accept(movement);
                    }
                    return;
                }
                if (movement != null) movement.close();
                context = next;
                lastResult=null;
                int localId = context.playerId(sessionId).orElseThrow();
                movement = new MovementClientController(network, localId, ui, () -> dispatch(this::requestFullState),
                        error->{if(context!=null&&context.matchId().equals(next.matchId()))fail(error);}, context.matchId(), false);
                movement.setTransportErrorHandler(this::connectionLost);
                started.accept(movement);
            }
            case PacketType.GAME_SNAPSHOT, PacketType.FULL_STATE_SYNC, PacketType.BOMB_PLANTED, PacketType.BOMB_EXPLODE,
                    PacketType.ITEM_SPAWN,PacketType.ITEM_PICKED_UP,PacketType.PLAYER_DIE -> {
                if (movement == null) return;
                Recovery r=recovery;
                if(r!=null) {
                    synchronized(recoveryLock) {
                        if(recovery!=r||recoveryState!=RecoveryState.WAITING_FULL||!r.metadata)return;
                    }
                    if(packet.getType()!=PacketType.GAME_SNAPSHOT&&packet.getType()!=PacketType.FULL_STATE_SYNC)return;
                    Packet body;
                    try {body=MatchEnvelopeCodec.unwrap(packet,r.match.matchId());}
                    catch(IOException staleMatch){return;}
                    com.bombit.server.game.movement.GameStateUpdate update;
                    try {update=com.bombit.server.game.movement.SnapshotPacketCodec.decode(body);}
                    catch(IOException invalid){fail(invalid);return;}
                    if(!update.isFullState())return;
                    movement.acceptOnUiThread(packet);
                    synchronized(recoveryLock) {
                        if(recovery!=r||recoveryState!=RecoveryState.WAITING_FULL||movement.isClosed()
                                ||movement.world().needsFullState()||movement.lastAppliedFullTick()!=update.getTick())return;
                        movement.resumeAfterRecovery();
                        recovery=null;reconnecting.set(false);recoveryState=RecoveryState.CONNECTED;
                    }
                    recoveryListener.accept(recoveryState);status.accept("Reconnected. Match synchronized.");return;
                }
                movement.acceptOnUiThread(packet);
            }
            case PacketType.ERROR -> {
                if(recovery!=null) {
                    String code=SessionPacketCodec.errorCode(packet);
                    if(code.equals("RATE_LIMITED")||code.equals("NOT_READY"))return;
                    fail(new IOException("Session recovery rejected: "+code));return;
                }
                if(lobby!=null) { lobby.accept(packet); return; }
                String code=SessionPacketCodec.errorCode(packet);
                String message=switch(code) {
                    case "ROOM_FULL" -> "The demo room is full.";
                    case "ROOM_PLAYING" -> "A match is already running. Wait for everyone to leave.";
                    case "RATE_LIMITED" -> "Too many requests. Please restart this client.";
                    default -> "The server rejected this request ("+code+").";
                };
                fail(new IOException(message));
            }
            default -> { }
        }
    }
    /** Called on the UI owner thread. Rate-limit requests without blocking the UI. */
    public void requestFullState() {
        if (closed.get() || reconnecting.get() || context == null || !resyncQueued.compareAndSet(false, true)) return;
        var match = context.matchId();
        long epoch=wireEpoch.get();
        long tick = movement == null ? -1 : movement.world().getTick();
        long delay = Math.max(0, lastResync + TimeUnit.MILLISECONDS.toNanos(1100) - System.nanoTime());
        try {
            control.schedule(() -> {
                try {
                    if (!closed.get() && !reconnecting.get() && wireEpoch.get()==epoch && context!=null && context.matchId().equals(match)) {
                        lastResync = System.nanoTime();
                        var request = new ResyncPacketCodec.Request(tick, ResyncPacketCodec.Reason.BASELINE_MISMATCH);
                        network.send(MatchEnvelopeCodec.wrap(match, ResyncPacketCodec.encode(request)));
                    }
                } catch (Exception error) {
                    if (autoReconnect && context != null && !closed.get() && sessionId != null && reconnectToken != null) {
                        connectionLost(error);
                    } else {
                        fail(error);
                    }
                }
                finally { resyncQueued.set(false); }
            }, delay, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException stopping) { resyncQueued.set(false); }
    }
    private void connectionLost(Throwable error) {
        if(closed.get())return;
        Recovery existing=recovery;
        if(existing!=null){retry(existing,wireEpoch.get());return;}
        if(!autoReconnect||context==null||sessionId==null||reconnectToken==null) {
            fail(error==null?new IOException("Server disconnected"):error);return;
        }
        Recovery r;
        synchronized(recoveryLock) {
            if(closed.get()||recovery!=null||context==null)return;
            r=new Recovery(context,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeouts.totalMillis()),wireEpoch.get());
            recovery=r;reconnecting.set(true);recoveryState=RecoveryState.RECONNECTING;
            if(movement!=null)movement.suspendForRecovery();
        }
        dispatch(()->{if(recovery==r&&movement!=null)movement.prepareRecoveryOnUi();});
        publishRecoveryStatus();scheduleAttempt(r,0);
    }
    private void publishRecoveryStatus() {
        Recovery r=recovery;
        dispatch(()->{
            if(recovery!=r||r==null)return;
            recoveryListener.accept(recoveryState);
            status.accept(switch(recoveryState){
                case WAITING_ACK -> "Reconnecting: waiting for session acknowledgement...";
                case WAITING_FULL -> "Reconnecting: synchronizing match state. Input is locked.";
                default -> "Connection lost. Reconnecting to match...";
            });
        });
    }
    private void scheduleAttempt(Recovery r,long delayMillis) {
        try {control.schedule(()->attempt(r),delayMillis,TimeUnit.MILLISECONDS);}
        catch(RejectedExecutionException stopping){if(!closed.get())fail(new IOException("Recovery worker stopped",stopping));}
    }
    private void attempt(Recovery r) {
        if(closed.get()||recovery!=r)return;
        // Dừng/join reader cũ trên worker; sau đó mới đổi epoch và mở socket mới.
        // Nhờ vậy không gắn packet cũ đang chờ UI sang attempt mới.
        network.disconnect();
        long epoch;
        synchronized(recoveryLock) {
            if(closed.get()||recovery!=r)return;
            if(System.nanoTime()>=r.deadline)return; // watchdog đóng phiên ngoài khóa.
            epoch=wireEpoch.incrementAndGet();r.epoch=epoch;r.metadata=false;r.attempts++;
            recoveryState=RecoveryState.WAITING_ACK;
            r.phaseDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeouts.ackMillis());
        }
        dispatch(()->{if(recovery==r&&wireEpoch.get()==epoch&&movement!=null)movement.prepareRecoveryOnUi();});
        publishRecoveryStatus();
        try {
            long remaining=Math.max(1,TimeUnit.NANOSECONDS.toMillis(r.deadline-System.nanoTime()));
            network.connect(host,port,(int)Math.min(timeouts.connectMillis(),remaining));
            synchronized(recoveryLock) {
                if(closed.get()||recovery!=r||r.epoch!=epoch||recoveryState!=RecoveryState.WAITING_ACK)return;
            }
            receivedNanos=System.nanoTime();network.send(SessionPacketCodec.reconnectRequest(sessionId,reconnectToken));
        } catch(IOException|RuntimeException failed) {retry(r,epoch);}
    }
    private void retry(Recovery r,long epoch) {
        synchronized(recoveryLock) {
            if(closed.get()||recovery!=r||r.epoch!=epoch||recoveryState==RecoveryState.RECONNECTING)return;
            recoveryState=RecoveryState.RECONNECTING;
            if(movement!=null)movement.suspendForRecovery();
        }
        publishRecoveryStatus();
        scheduleAttempt(r,Math.min(500,timeouts.retryMillis()*Math.max(1,r.attempts)));
    }
    private void checkRecoveryTimeouts() {
        Recovery r;long epoch;boolean phaseExpired,terminated=false;
        synchronized(recoveryLock) {
            r=recovery;if(closed.get()||r==null)return;
            long now=System.nanoTime();
            if(now>=r.deadline)terminated=beginClose();
            phaseExpired=(recoveryState==RecoveryState.WAITING_ACK||recoveryState==RecoveryState.WAITING_FULL)&&now>=r.phaseDeadline;
            epoch=r.epoch;
        }
        if(terminated)finishClose(new IOException("Session recovery deadline exceeded"));
        else if(phaseExpired)retry(r,epoch);
    }
    private boolean beginClose() {
        synchronized(recoveryLock) {
            if(!closed.compareAndSet(false,true))return false;
            recovery=null;reconnecting.set(false);recoveryState=RecoveryState.CLOSED;return true;
        }
    }
    private void fail(Throwable error) { if(beginClose())finishClose(error); }
    private void finishClose(Throwable error) {
        // Không giữ recoveryLock khi đóng/join network; reader/UI có thể đang chờ khóa đó.
        control.shutdownNow();recoveryWatchdog.shutdownNow();network.close();
        if(movement!=null)movement.close();
        try{ui.execute(() -> {
            context=null;lobby=null;if(error==null)movement=null;
            recoveryListener.accept(recoveryState);if(error!=null)errors.accept(error);
        });}catch(RejectedExecutionException stopping){}
    }
    public MatchContext context() { return context; }
    public MovementClientController movement() { return movement; }
    public int localId() { return context.playerId(sessionId).orElseThrow(); }
    @Override public void close() {
        if(beginClose())finishClose(null);
    }
    public boolean isClosed(){return closed.get();}
    public boolean workersStopped(){return control.isTerminated()&&recoveryWatchdog.isTerminated()&&!network.isReaderAlive();}
    public int listenerCount(){return network.listenerCount();}
    // Quan sát budget dispatch trong test interleaving UI; không đọc world từ reader.
    int pendingUiActions(){return 256-uiSlots.availablePermits();}
}

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
public final class GameClientConnection implements AutoCloseable {
    private final ClientNetwork network = new ClientNetwork();
    private final Executor ui;
    private final Consumer<MovementClientController> started;
    private final Consumer<String> status;
    private final Consumer<Throwable> errors;
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Game-Client-Control"); thread.setDaemon(true); return thread;
    });
    private final Semaphore uiSlots = new Semaphore(256);
    private final Semaphore controlSlots=new Semaphore(128);
    private final java.util.concurrent.atomic.AtomicLong uiBytes=new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(), resyncQueued = new AtomicBoolean();
    private volatile long receivedNanos = System.nanoTime();
    private volatile long lastResync;
    private int pingId;
    private String sessionId;
    private volatile int matchLocalId = -1;
    private volatile MatchContext context;
    private Consumer<Packet> lobby;
    private volatile MovementClientController movement;
    private com.bombit.common.dto.MatchResult lastResult;
    public com.bombit.common.dto.MatchResult lastResult(){return lastResult;}

    public GameClientConnection(Executor ui, Consumer<MovementClientController> started,
                                Consumer<String> status, Consumer<Throwable> errors) {
        this.ui=ui; this.started=started; this.status=status; this.errors=errors;
        network.setPacketQueueEnabled(false);
        network.addPacketListener(packet -> {
            receivedNanos = System.nanoTime();
            dispatch(() -> packet(packet),6+packet.getJsonPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        });
        network.setOnDisconnect(error -> fail(error == null ? new IOException("Server disconnected") : error));
    }
    /** Lobby events and command errors remain recoverable on the UI executor. */
    public void setLobbyListener(Consumer<Packet> listener) { lobby=listener; }
    public String sessionId() { return sessionId; }
    public void send(Packet packet) {
        if(closed.get())return;
        if(!controlSlots.tryAcquire()){fail(new IOException("Client control queue full"));return;}
        try { control.execute(() -> { try { if(!closed.get()) network.send(packet); } catch(Exception e) { fail(e); } finally{controlSlots.release();} }); }
        catch(RejectedExecutionException stopping) {controlSlots.release();}
    }
    public void connect(String host, int port, String name) throws IOException {
        try {
            Packet connect = SessionPacketCodec.connectRequest(name);
            SessionPacketCodec.connect(connect);
            network.connect(host, port);
            network.send(connect);
            control.scheduleAtFixedRate(() -> {
                if (closed.get()) return;
                try {
                    if (System.nanoTime() - receivedNanos > TimeUnit.SECONDS.toNanos(10)) throw new IOException("Server heartbeat timeout");
                    network.send(new Packet(PacketType.PING, "{\"pingId\":" + ++pingId + "}"));
                } catch (Exception error) { fail(error); }
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
                sessionId = SessionPacketCodec.decodeAcknowledgement(packet).playerId();
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
                if(movement!=null) movement.close(); movement=null; context=null;
                if(lobby!=null) lobby.accept(packet);
            }
            case PacketType.GAME_STARTED -> {
                if (sessionId == null) throw new IllegalStateException("Game started before handshake");
                MatchContext next = DemoPacketCodec.decodeStarted(packet);
                if (context != null && context.matchId().equals(next.matchId())) return;
                if (movement != null) movement.close();
                context = next;
                lastResult=null;
                int localId = context.playerId(sessionId).orElseThrow();
                matchLocalId = localId;
                movement = new MovementClientController(network, localId, ui, () -> dispatch(this::requestFullState),
                        error->{if(context!=null&&context.matchId().equals(next.matchId()))fail(error);}, context.matchId(), false);
                started.accept(movement);
            }
            case PacketType.GAME_SNAPSHOT, PacketType.FULL_STATE_SYNC, PacketType.BOMB_PLANTED, PacketType.BOMB_EXPLODE,
                    PacketType.ITEM_SPAWN,PacketType.ITEM_PICKED_UP,PacketType.PLAYER_DIE -> {
                if (movement == null) return;
                movement.acceptOnUiThread(packet);
            }
            case PacketType.ERROR -> {
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
        if (closed.get() || context == null || !resyncQueued.compareAndSet(false, true)) return;
        var match = context.matchId();
        long tick = movement == null ? -1 : movement.world().getTick();
        long delay = Math.max(0, lastResync + TimeUnit.MILLISECONDS.toNanos(1100) - System.nanoTime());
        try {
            control.schedule(() -> {
                try {
                    if (!closed.get() && context!=null && context.matchId().equals(match)) {
                        lastResync = System.nanoTime();
                        var request = new ResyncPacketCodec.Request(tick, ResyncPacketCodec.Reason.BASELINE_MISMATCH);
                        network.send(MatchEnvelopeCodec.wrap(match, ResyncPacketCodec.encode(request)));
                    }
                } catch (Exception error) { fail(error); }
                finally { resyncQueued.set(false); }
            }, delay, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException stopping) { resyncQueued.set(false); }
    }
    private void fail(Throwable error) {
        if (!closed.compareAndSet(false, true)) return;
        network.close(); control.shutdownNow();
        if(movement!=null)movement.close();
        try{ui.execute(() -> {context=null;lobby=null;errors.accept(error);});}catch(RejectedExecutionException stopping){}
    }
    public MatchContext context() { return context; }
    public MovementClientController movement() { return movement; }
    /** Remains available after GAME_OVER clears the match context so results UI can identify this player. */
    public int localId() {
        MatchContext active = context;
        if (active != null) return active.playerId(sessionId).orElseThrow();
        if (matchLocalId > 0) return matchLocalId;
        throw new IllegalStateException("No match player ID is available");
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        network.close(); control.shutdownNow();
        if(movement!=null)movement.close();
        try{ui.execute(() -> {movement=null;context=null;lobby=null;});}catch(RejectedExecutionException stopping){}
    }
    public boolean isClosed(){return closed.get();}
    public boolean workersStopped(){return control.isTerminated()&&!network.isReaderAlive();}
    public int listenerCount(){return network.listenerCount();}
}

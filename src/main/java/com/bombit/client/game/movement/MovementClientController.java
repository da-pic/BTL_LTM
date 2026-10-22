package com.bombit.client.game.movement;

import com.bombit.client.network.ClientNetwork;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.movement.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Nối ClientNetwork với replica/prediction/interpolation, không tự tạo socket.
 * uiExecutor phải tuần tự (ví dụ SwingUtilities::invokeLater). Gọi các getter từ UI thread.
 * Worker gửi có queue hữu hạn; khi lỗi thì dừng gửi và báo caller để dừng input/đóng phiên.
 */
public final class MovementClientController implements AutoCloseable {
    private final ClientNetwork network;
    private final int localId;
    private final Executor ui;
    private final Consumer<Throwable> errors;
    private final Runnable resync;
    private final Consumer<Packet> listener;
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), r -> { var t=new Thread(r,"Movement-Sender"); t.setDaemon(true); return t; });
    private final ClientWorldState world = new ClientWorldState();
    private final InterpolationController interpolation = new InterpolationController();
    private PredictionController prediction;
    private ReconciliationController reconciliation;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean stopping=new java.util.concurrent.atomic.AtomicBoolean();
    private final Semaphore uiSlots=new Semaphore(256);
    private final java.util.concurrent.atomic.AtomicLong uiBytes=new java.util.concurrent.atomic.AtomicLong();
    private long receivedNanos;
    private boolean resyncPending;
    private final java.util.UUID matchId;
    private final boolean subscribed;
    private final com.bombit.client.game.bomb.BombScene bombs=new com.bombit.client.game.bomb.BombScene();
    private long plantRequest;
    private int respawns;

    public MovementClientController(ClientNetwork network,int localId,Executor ui,
                                    Runnable resync,Consumer<Throwable> errors) {
        this(network,localId,ui,resync,errors,null,true);
    }
    public MovementClientController(ClientNetwork network,int localId,Executor ui,
                                    Runnable resync,Consumer<Throwable> errors,java.util.UUID matchId,boolean subscribe) {
        this.network=network; this.localId=localId; this.ui=ui; this.resync=resync; this.errors=errors;
        this.matchId=matchId; this.subscribed=subscribe;
        listener = this::acceptPacket;
        if(subscribe) {
            network.addPacketListener(PacketType.GAME_SNAPSHOT,listener);
            network.addPacketListener(PacketType.FULL_STATE_SYNC,listener);
            network.addPacketListener(PacketType.BOMB_PLANTED,listener);
            network.addPacketListener(PacketType.BOMB_EXPLODE,listener);
            for(short type:new short[]{PacketType.ITEM_SPAWN,PacketType.ITEM_PICKED_UP,PacketType.PLAYER_DIE})network.addPacketListener(type,listener);
        }
    }
    /** Entry point for a permanent application listener installed before CONNECT. */
    public void acceptPacket(Packet packet) {
        receive(packet,false);
    }
    /** Permanent application listener is already on the UI owner: apply before a following GAME_OVER. */
    public void acceptOnUiThread(Packet packet){receive(packet,true);}
    private void receive(Packet packet,boolean onUi) {
        if(closed) return;
        int bytes=6+packet.getJsonPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            try {
                if(matchId!=null) {
                    try { packet=MatchEnvelopeCodec.unwrap(packet,matchId); }
                    catch(ProtocolException stale) { return; }
                }
                if(packet.getType()==PacketType.BOMB_PLANTED||packet.getType()==PacketType.BOMB_EXPLODE) {
                    var notice=BombPacketCodec.decodeNotice(packet);
                    Runnable action=()->{if(!closed) bombs.notice(notice);};if(onUi)action.run();else dispatchUi(action,bytes);return;
                }
                if(packet.getType()==PacketType.ITEM_SPAWN||packet.getType()==PacketType.ITEM_PICKED_UP||packet.getType()==PacketType.PLAYER_DIE){
                    GameplayPacketCodec.validateNotice(packet);return;
                }
                var update=SnapshotPacketCodec.decode(packet);
                if(onUi)apply(update);else dispatchUi(() -> { if(!closed) apply(update); },bytes);
            } catch (Exception e) { fail(e); }
    }
    private void apply(GameStateUpdate update) {
        try {
            var result=world.apply(update);
            if(result == ClientWorldState.Result.NEED_FULL_STATE) {
                if(!resyncPending) { resyncPending=true; sender.execute(resync); } return;
            }
            if(result != ClientWorldState.Result.APPLIED) return;
            resyncPending=false; receivedNanos=System.nanoTime();
            bombs.synchronize(world,update.getBombFrame(),System.currentTimeMillis(),receivedNanos);
            var player=world.getPlayers().get(localId);
            if(player == null) { fail(new IllegalStateException("Local player removed")); return; }
            if(prediction == null) {
                var map=new GameMap(world.getWidth(),world.getHeight(),world.getTileSize());
                prediction=new PredictionController(map,player);
                reconciliation=new ReconciliationController(prediction);
            }
            var frame=world.getMatchFrame();
            if(frame!=null){
                var stats=frame.players().stream().filter(p->p.playerId()==localId).findFirst().orElseThrow();
                if(stats.respawns()!=respawns){prediction.respawn();respawns=stats.respawns();}
            }
            reconciliation.reconcile(world,player); interpolation.record(world);
        } catch (RuntimeException e) { fail(e); }
    }
    /** InputController gọi sau prediction; chỉ enqueue, không chặn UI bằng socket write. */
    public void send(MovePacketCodec.Input input) {
        if(closed) throw new IllegalStateException("Movement client closed");
        enqueue(() -> {
            try { if(!closed) {
                Packet packet=MovePacketCodec.encode(input);
                network.send(matchId==null?packet:MatchEnvelopeCodec.wrap(matchId,packet));
            } }
            catch(Exception e) { fail(e); }
        });
    }
    public com.bombit.client.game.bomb.BombScene bombs() { return bombs; }
    /** No predicted bomb or damage; wait for the authoritative snapshot. */
    public void plantBomb() {
        var local=world.getPlayers().get(localId);
        if(!isInputReady()||!bombs.enabled()||local==null||!local.isAlive()||matchId==null) return;
        Packet command=BombPacketCodec.plant(++plantRequest);
        enqueue(()->{
            try { if(!closed) network.send(MatchEnvelopeCodec.wrap(matchId,command)); }
            catch(Exception e) { fail(e); }
        });
    }
    private void enqueue(Runnable task){
        try{sender.execute(task);}catch(RejectedExecutionException full){if(!closed)fail(full);}
    }
    private void fail(Throwable error) {
        if(!stop())return;
        try{ui.execute(() -> errors.accept(error));}catch(RejectedExecutionException stopping){}
    }
    private void dispatchUi(Runnable task,int bytes){
        if(!uiSlots.tryAcquire()){fail(new IllegalStateException("Movement display queue full"));return;}
        if(uiBytes.addAndGet(bytes)>4L*1024*1024){uiBytes.addAndGet(-bytes);uiSlots.release();fail(new IllegalStateException("Movement display byte budget full"));return;}
        try{ui.execute(()->{try{if(!closed)task.run();}finally{uiBytes.addAndGet(-bytes);uiSlots.release();}});}
        catch(RuntimeException e){uiBytes.addAndGet(-bytes);uiSlots.release();fail(e);}
    }
    public ClientWorldState world() { return world; }
    public PredictionController prediction() { return prediction; }
    public boolean isInputReady() { return !closed && prediction!=null && !world.needsFullState()
            && world.getMatchState()==com.bombit.server.game.core.GameState.MatchState.RUNNING; }
    public boolean isClosed() { return closed; }
    public InterpolationController interpolation() { return interpolation; }
    /** Timeline lùi 2 tick; clamp thêm tối đa 2 tick khi lâu không nhận dữ liệu. */
    public double renderTick() {
        return world.getTick()+Math.min(2,(System.nanoTime()-receivedNanos)/50_000_000.0)-2;
    }
    @Override public void close() {
        stop();
    }
    private boolean stop(){
        if(!stopping.compareAndSet(false,true))return false;
        closed=true;
        if(subscribed) {
            network.removePacketListener(PacketType.GAME_SNAPSHOT,listener);
            network.removePacketListener(PacketType.FULL_STATE_SYNC,listener);
            network.removePacketListener(PacketType.BOMB_PLANTED,listener);
            network.removePacketListener(PacketType.BOMB_EXPLODE,listener);
            for(short type:new short[]{PacketType.ITEM_SPAWN,PacketType.ITEM_PICKED_UP,PacketType.PLAYER_DIE})network.removePacketListener(type,listener);
        }
        sender.shutdownNow();
        return true;
    }
    public boolean isSenderStopped(){return sender.isTerminated();}
}

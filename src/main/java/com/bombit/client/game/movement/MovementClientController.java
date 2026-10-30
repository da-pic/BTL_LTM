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
    private final com.bombit.client.ui.game.bomb.BombScene bombs=new com.bombit.client.ui.game.bomb.BombScene();
    private long plantRequest;
    private long rewindRequest;
    private String rewindFeedback="";
    public String rewindFeedback(){return rewindFeedback;}
    public void acceptRewindResult(com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.RewindResult result){
        rewindFeedback=switch(result.outcome()){
            case ACCEPTED->"Đã đảo toàn bản đồ về 40 tick trước";
            case NOT_ENOUGH_HISTORY->"Chưa đủ 2 giây lịch sử — lượt chưa bị tiêu";
            case COOLDOWN->"Rewind toàn trận đang hồi — lượt chưa bị tiêu";
            case REWIND_BUSY->"Người khác vừa rewind — lượt chưa bị tiêu";
            case ALREADY_USED->"Bạn đã dùng hết lượt rewind";
            case NOT_ELIGIBLE->"Chỉ người còn sống, đang kết nối mới được rewind";
            case NOT_RUNNING->"Trận đã kết thúc hoặc hết thời gian";
            default->"Yêu cầu rewind đã cũ hoặc không hợp lệ";
        };
    }
    private com.bombit.common.dto.ParadoxFrame paradoxFrame;
    public com.bombit.common.dto.ParadoxFrame paradox(){return paradoxFrame;}
    private int respawns;
    private volatile boolean recoverySuspended;
    private boolean resetAfterRecovery;
    private long lastAppliedFullTick=-1;
    private final java.util.concurrent.atomic.AtomicLong inputGeneration=new java.util.concurrent.atomic.AtomicLong();
    private volatile Consumer<Throwable> transportErrors;
    private volatile com.bombit.common.dto.MatchStartMetadata currentMetadata;
    private boolean serverInputEnabled=true;
    private ShadowReplica shadowReplica;
    public ShadowReplica shadow(){return shadowReplica;}
    public com.bombit.common.dto.MatchStartMetadata metadata(){return currentMetadata;}
    private String plantFeedback="";
    public String plantFeedback(){return plantFeedback;}
    public long estimatedMatchMillis(){return shadowReplica==null?0:Math.max(0,shadowReplica.matchTimeMillis())+Math.min(1000,Math.max(0,(System.nanoTime()-receivedNanos)/1_000_000));}
    /** Kết quả đã được application codec kiểm tra; UI chỉ thông báo, không tự tạo scan/bom dự đoán. */
    public void acceptPlantResult(Packet packet){
        var m=JsonV3.object(packet.getJsonPayload(),16384);String kind="SCAN".equals(m.get("bombType"))?"Scan":"Bom";
        plantFeedback="ACCEPTED".equals(m.get("status"))?kind+" đã đặt":switch(String.valueOf(m.get("errorCode"))){
            case "SCAN_COOLDOWN"->"Scan đang hồi";case "BOMB_LIMIT_REACHED"->"Đã đầy quota bom/scan";
            case "CELL_OCCUPIED","INVALID_CELL"->"Ô này không thể đặt bom/scan";case "RATE_LIMITED"->"Thao tác quá nhanh";
            default->kind+" bị từ chối";};
    }
    /** UI owner: epoch mới bỏ prediction/interpolation cũ, chỉ full đúng context mới mở input. */
    public void bindCurrentMetadata(com.bombit.common.dto.MatchStartMetadata metadata){
        if(!metadata.identity().matchId().equals(matchId)||metadata.stream().recipientPlayerId()!=localId)throw new IllegalArgumentException("Wrong controller context");
        if(currentMetadata!=null){
            if(metadata.stream().viewEpoch()<=currentMetadata.stream().viewEpoch()
                    ||metadata.stream().timelineEpoch()<currentMetadata.stream().timelineEpoch())throw new IllegalArgumentException("Stale epoch");
            if(!metadata.configuration().equals(currentMetadata.configuration())||!metadata.players().equals(currentMetadata.players())
                    ||!metadata.identity().equals(currentMetadata.identity()))throw new IllegalArgumentException("Changed match identity");
        }
        if(metadata.configuration().gameMode()==com.bombit.common.dto.GameMode.SHADOW_RELAY){
            if(shadowReplica==null)shadowReplica=new ShadowReplica(world,metadata);else shadowReplica.bind(metadata);
        }else shadowReplica=null;
        currentMetadata=metadata;paradoxFrame=null;rewindFeedback="";serverInputEnabled=false;suspendForRecovery();prepareRecoveryOnUi();bombs.clear();
    }
    public void acceptSnapshotV6(com.bombit.common.dto.RecipientSnapshotV6 snapshot){
        if(currentMetadata!=null){
            var mode=currentMetadata.configuration().gameMode();
            if((snapshot instanceof com.bombit.common.dto.ParadoxSnapshotV6)!=(mode==com.bombit.common.dto.GameMode.PARADOX)
                    ||(snapshot instanceof com.bombit.common.dto.ShadowSnapshotV6)!=(mode==com.bombit.common.dto.GameMode.SHADOW_RELAY))return;
        }
        if(currentMetadata!=null&&currentMetadata.configuration().gameMode()==com.bombit.common.dto.GameMode.PARADOX){
            if(!(snapshot instanceof com.bombit.common.dto.ParadoxSnapshotV6 paradox)
                    ||paradox.paradox().timelineEpoch()!=currentMetadata.stream().timelineEpoch())return;
            if(paradoxFrame!=null&&(paradox.simulationTick()<paradoxFrame.simulationTick()
                    ||!paradoxFrame.availableCharges().containsAll(paradox.paradox().availableCharges())))throw new IllegalArgumentException("Paradox state regressed within timeline");
            serverInputEnabled=paradox.inputEnabled();apply(paradox.update());
            if(world.getTick()==paradox.update().getTick()&&!world.needsFullState())paradoxFrame=paradox.paradox();
            return;
        }
        if(snapshot instanceof com.bombit.common.dto.ShadowSnapshotV6 shadow){
            if(shadowReplica==null)throw new IllegalStateException("Shadow metadata missing");apply(shadow.update(),shadow);
        }else{serverInputEnabled=snapshot.inputEnabled();apply(snapshot.update());}
    }
    public void acceptNoticeV3(Packet packet){
        var fields=new java.util.LinkedHashMap<>(JsonV3.object(packet.getJsonPayload(),16384));
        fields.put("occurredAtMillis",fields.remove("occurredAtMatchMillis"));
        if(fields.containsKey("explodeAtMatchMillis"))fields.put("explodeAtMillis",fields.remove("explodeAtMatchMillis"));
        var legacy=JsonV3.packet(packet.getType(),fields);GameplayPacketCodec.validateNotice(legacy);
        if(packet.getType()==PacketType.BOMB_PLANTED||packet.getType()==PacketType.BOMB_EXPLODE)bombs.notice(BombPacketCodec.decodeNotice(legacy));
    }
    private Packet scope(Packet packet,com.bombit.common.dto.MatchStartMetadata metadata)throws java.io.IOException{return metadata!=null?MatchEnvelopeV3Codec.command(matchId,metadata.stream().timelineEpoch(),packet):matchId==null?packet:MatchEnvelopeCodec.wrap(matchId,packet);}

    /** Chỉ lỗi gửi mạng dùng recovery; lỗi payload/world vẫn fail và đóng controller. */
    public void setTransportErrorHandler(Consumer<Throwable> handler) { transportErrors=handler; }
    /** Reader được gọi hàm này: khóa input ngay và bỏ task chưa gửi của connection cũ. */
    public void suspendForRecovery() {
        recoverySuspended=true;inputGeneration.incrementAndGet();sender.getQueue().clear();
    }
    /** Chỉ gọi trên UI: giữ world để vẽ, bỏ prediction pending và đệm nội suy cũ. */
    public void prepareRecoveryOnUi() {
        resetAfterRecovery=true;world.requireFullState();interpolation.clear();
        var local=world.getPlayers().get(localId);
        if(prediction!=null&&local!=null)prediction.resetAfterRecovery(local);
    }
    public void resumeAfterRecovery() {
        if(closed||resetAfterRecovery||world.needsFullState())throw new IllegalStateException("Recovery full not applied");
        recoverySuspended=false;
    }
    public boolean isRecovering() { return recoverySuspended; }
    public long inputGeneration() { return inputGeneration.get(); }
    public long lastAppliedFullTick() { return lastAppliedFullTick; }

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
        apply(update,null);
    }
    private void apply(GameStateUpdate update,com.bombit.common.dto.ShadowSnapshotV6 shadow) {
        try {
            if(resetAfterRecovery&&!update.isFullState())return;
            var result=shadow==null?world.apply(update):shadowReplica.apply(shadow);
            if(result == ClientWorldState.Result.NEED_FULL_STATE) {
                if(!resyncPending) { resyncPending=true; sender.execute(resync); } return;
            }
            if(result != ClientWorldState.Result.APPLIED) return;
            if(shadow!=null)serverInputEnabled=shadow.inputEnabled();
            resyncPending=false; receivedNanos=System.nanoTime();
            bombs.synchronize(world,update.getBombFrame(),System.currentTimeMillis(),receivedNanos);
            var player=world.getPlayers().get(localId);
            if(player == null) {
                if(shadow!=null){serverInputEnabled=false;resetAfterRecovery=false;interpolation.record(world);if(update.isFullState())lastAppliedFullTick=update.getTick();return;}
                fail(new IllegalStateException("Local player removed")); return;
            }
            if(prediction == null) {
                var map=new GameMap(world.getWidth(),world.getHeight(),world.getTileSize());
                prediction=new PredictionController(map,player);
                reconciliation=new ReconciliationController(prediction);
            }
            if(resetAfterRecovery) {
                prediction.resetAfterRecovery(player);interpolation.clear();resetAfterRecovery=false;
            }
            var frame=world.getMatchFrame();
            if(frame!=null){
                var stats=frame.players().stream().filter(p->p.playerId()==localId).findFirst().orElseThrow();
                if(stats.respawns()!=respawns){prediction.respawn();respawns=stats.respawns();}
            }
            reconciliation.reconcile(world,player); interpolation.record(world);
            if(update.isFullState())lastAppliedFullTick=update.getTick();
        } catch (RuntimeException e) { fail(e); }
    }
    /** InputController gọi sau prediction; chỉ enqueue, không chặn UI bằng socket write. */
    public void send(MovePacketCodec.Input input) {
        if(closed) throw new IllegalStateException("Movement client closed");
        if(recoverySuspended)return;
        long generation=inputGeneration.get();
        var metadata=currentMetadata;
        enqueue(() -> {
            try { if(!closed&&!recoverySuspended&&generation==inputGeneration.get()) {
                Packet packet=MovePacketCodec.encode(input);
                network.send(scope(packet,metadata));
            } }
            catch(Exception e) { transportFailed(e,generation); }
        });
    }
    public com.bombit.client.ui.game.bomb.BombScene bombs() { return bombs; }
    /** No predicted bomb or damage; wait for the authoritative snapshot. */
    public void plantBomb() {
        plant(com.bombit.common.dto.BombType.NORMAL);
    }
    public void plantScan() {
        if(shadowReplica!=null)plant(com.bombit.common.dto.BombType.SCAN);
    }
    private void plant(com.bombit.common.dto.BombType type) {
        var local=world.getPlayers().get(localId);
        if(!isInputReady()||!bombs.enabled()||local==null||!local.isAlive()||matchId==null) return;
        Packet command=currentMetadata==null?BombPacketCodec.plant(++plantRequest):JsonV3.packet(PacketType.PLANT_BOMB,JsonV3.body("requestId",++plantRequest,"bombType",type.name()));
        long generation=inputGeneration.get();
        var metadata=currentMetadata;
        enqueue(()->{
            try { if(!closed&&!recoverySuspended&&generation==inputGeneration.get())network.send(scope(command,metadata)); }
            catch(Exception e) { transportFailed(e,generation); }
        });
    }
    /** API điều khiển Paradox; ACK/result không tự mở input, chỉ full mới quyết định. */
    public void rewind(){
        if(!isInputReady()||currentMetadata==null||currentMetadata.configuration().gameMode()!=com.bombit.common.dto.GameMode.PARADOX)return;
        var metadata=currentMetadata;long generation=inputGeneration.get();var command=ParadoxCodec.rewind(++rewindRequest);
        enqueue(()->{try{if(!closed&&!recoverySuspended&&generation==inputGeneration.get())network.send(scope(command,metadata));}
            catch(Exception e){transportFailed(e,generation);}});
    }
    private void transportFailed(Throwable error,long generation) {
        if(closed||generation!=inputGeneration.get())return;
        var handler=transportErrors;
        if(handler==null) {fail(error);return;}
        suspendForRecovery();handler.accept(error);
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
    public boolean isInputReady() { return !closed && serverInputEnabled && !recoverySuspended && prediction!=null && !world.needsFullState()
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

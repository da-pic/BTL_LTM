package com.bombit.server.game.runtime;

import com.bombit.server.game.core.*;
import com.bombit.common.dto.MatchConfiguration;
import com.bombit.server.game.movement.GameStateUpdate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** API phòng cho TV1/TV4: chỉ submit command và lấy snapshot bất biến.
 * Không mở socket, không encode packet và không trả GameState cho network thread.
 * Một runtime có một GameLoop; extension factory được gọi trước start để gắn world TV3.
 */
public final class GameRuntime implements AutoCloseable {
    private final String roomId;
    private final UUID matchId = UUID.randomUUID();
    private final Map<String,Integer> players;
    private final GameLoop loop;
    private final GameplayExtension extension;
    private final MatchConfiguration configuration;
    private final boolean framePipeline;
    GameRuntime(String roomId,GameState state,Map<String,Integer> players,GameplayExtension extension) {
        this(roomId,state,players,extension,null);
    }
    /** Null chỉ dành cho constructor legacy: không dựng metadata luật giả từ callback cũ. */
    GameRuntime(String roomId,GameState state,Map<String,Integer> players,GameplayExtension extension,MatchConfiguration configuration) {
        this(roomId,state,players,extension,configuration,false,System::nanoTime,System::currentTimeMillis);
    }
    GameRuntime(String roomId,GameState state,Map<String,Integer> players,GameplayExtension extension,MatchConfiguration configuration,
                boolean frames,java.util.function.LongSupplier nanos,java.util.function.LongSupplier epoch) {
        this.roomId=roomId;this.players=Map.copyOf(players);
        this.extension=extension;
        this.configuration=configuration;
        this.framePipeline=frames;
        long period=configuration==null?50_000_000L:configuration.effectiveRules().common().tickTargetMillis()*1_000_000L;
        loop=new GameLoop(state,new com.bombit.server.game.movement.MovementSystem(),extension,4096,period,frames,nanos,epoch);
    }
    void start(){loop.start();}
    public String roomId(){return roomId;}
    /** New UUID per runtime, including replay in the same room; does not change snapshot-v2 bytes. */
    public UUID matchId(){return matchId;}
    /** Cấu hình gắn suốt đời runtime; reconnect không resolve lại hoặc đổi UUID trận. */
    public Optional<MatchConfiguration> configuration(){return Optional.ofNullable(configuration);}
    /** Scalar từ ảnh đã publish, phục vụ barrier; không đọc GameState trên network thread. */
    public long lastPublishedTick(){var update=loop.createUpdate();return update==null?-1L:update.getTick();}
    public OptionalInt playerId(String sessionId){Integer id=players.get(sessionId);return id==null?OptionalInt.empty():OptionalInt.of(id);}
    public boolean submit(GameCommand command){return loop.submitCommand(command);}
    public boolean submit(GameCommand command,long timelineEpoch){return loop.submitCommand(command,timelineEpoch);}
    public long timelineEpoch(){return loop.getTimelineEpoch();}
    public Optional<com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.Status> paradoxStatus(){
        return extension instanceof com.bombit.server.game.mode.paradox.ParadoxGameplayExtension paradox
                ?Optional.ofNullable(paradox.status()):Optional.empty();
    }
    public long rejectedCommandCount(){return loop.getRejectedCommandCount();}
    /** Lấy update theo thứ tự; TV4 mã hóa/broadcast. null nếu hết thời gian hoặc chưa có thay đổi. */
    public GameStateUpdate pollUpdate(long timeout,TimeUnit unit)throws InterruptedException{if(framePipeline)throw new IllegalStateException("Use pollWorldFrame");return loop.pollOutboundUpdate(timeout,unit);}
    public com.bombit.server.game.replication.WorldFrame pollWorldFrame(long timeout,TimeUnit unit)throws InterruptedException{return loop.pollWorldFrame(timeout,unit);}
    public long lastCompletedTick(){return loop.getTick();}
    public GameStateUpdate createUpdate(){return loop.createUpdate();}
    public com.bombit.server.game.replication.WorldFrame latestWorldFrame(){return loop.latestWorldFrame();}
    public void requestFullState(){loop.requestFullState();}
    public boolean isRunning(){return loop.isRunning();}
    public boolean isThreadAlive(){return loop.isThreadAlive();}
    public Throwable failure(){return loop.getFailure();}
    @Override public void close(){loop.stop();}
}

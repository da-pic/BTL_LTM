package com.bombit.server.game.runtime;

import com.bombit.server.game.core.*;
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
    GameRuntime(String roomId,GameState state,Map<String,Integer> players,GameplayExtension extension) {
        this.roomId=roomId;this.players=Map.copyOf(players);
        loop=new GameLoop(state,new com.bombit.server.game.movement.MovementSystem(),extension,4096,50_000_000L);
    }
    void start(){loop.start();}
    public String roomId(){return roomId;}
    /** New UUID per runtime, including replay in the same room; does not change snapshot-v2 bytes. */
    public UUID matchId(){return matchId;}
    public OptionalInt playerId(String sessionId){Integer id=players.get(sessionId);return id==null?OptionalInt.empty():OptionalInt.of(id);}
    public boolean submit(GameCommand command){return loop.submitCommand(command);}
    public long rejectedCommandCount(){return loop.getRejectedCommandCount();}
    /** Lấy update theo thứ tự; TV4 mã hóa/broadcast. null nếu hết thời gian hoặc chưa có thay đổi. */
    public GameStateUpdate pollUpdate(long timeout,TimeUnit unit)throws InterruptedException{return loop.pollOutboundUpdate(timeout,unit);}
    public GameStateUpdate createUpdate(){return loop.createUpdate();}
    public void requestFullState(){loop.requestFullState();}
    public boolean isRunning(){return loop.isRunning();}
    public boolean isThreadAlive(){return loop.isThreadAlive();}
    public Throwable failure(){return loop.getFailure();}
    @Override public void close(){loop.stop();}
}

package com.bombit.server.game.movement;

import com.bombit.server.game.model.Direction;
import com.bombit.client.game.movement.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.runtime.*;

import com.bombit.server.session.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Hồi quy TV2: prediction/replay, interpolation, chống input trùng và TCP thật.
 * Socket chỉ nằm trong test harness, production dùng framework TV1/TV4.
 */
public final class MovementFeatureCheck {
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static GameState state() {
        var state=new GameState(new GameMap(17,15,32));
        state.addPlayer(new Player(1,32,32,24,24,100));state.startMatch();return state;
    }
    private static GameStateUpdate full(GameState state,long tick) {
        return new StateDeltaBuilder().buildUpdate(state,tick,true);
    }
    public static void main(String[] args) throws Exception {
        predictionAndInterpolation(); bombCollision(); runtimeAndTcp();
        System.out.println("PASS: prediction, reconciliation, interpolation, collision, runtime, TCP");
    }
    private static void predictionAndInterpolation() throws Exception {
        GameState state=state();var initial=full(state,1);var replica=new ClientWorldState();replica.apply(initial);
        var predicted=new PredictionController(new GameMap(17,15,32),initial.getPlayers().get(0));
        predicted.predict(Direction.RIGHT,true);predicted.predict(Direction.RIGHT,true);
        check(predicted.position().getX()==42,"predict two fixed steps");
        // Server xác nhận chỉ mẫu 1 tại x=35 (khác dự đoán); replay mẫu 2 phải tới x=40.
        var authority=new GameStateUpdate.PlayerData(1,35,32,24,24,100,Direction.RIGHT,true,true,1);
        new ReconciliationController(predicted).reconcile(replica,authority);
        check(predicted.position().getX()==40 && predicted.pendingCount()==1,"replay unacked only");
        var packet=SnapshotPacketCodec.decode(SnapshotPacketCodec.encode(
                GameStateUpdate.fullStateModel(2,17,15,32,GameState.MatchState.RUNNING,initial.mapTiles(),List.of(authority))));
        check(packet.getPlayers().get(0).getLastProcessedInput()==1,"ACK codec round trip");
        var interpolation=new InterpolationController();interpolation.record(replica);
        state.getPlayer(1).setPosition(52,32);replica.apply(full(state,3));interpolation.record(replica);
        check(interpolation.sample(1,2).x()==42,"interpolation midpoint");
        check(interpolation.sample(1,100).x()==52,"hold latest outside buffer");
        try { MovePacketCodec.decode(new Packet(PacketType.MOVE,"{\"playerId\":2}"));throw new AssertionError("invalid input accepted"); }
        catch(IllegalArgumentException expected) { }
    }
    /** Collision chỉ đọc ảnh entity; không kiểm thử luật item/bomb của TV3 trong module TV2. */
    private static void bombCollision() {
        GameState state=state();
        state.putEntity(new EntityData(EntityData.Kind.BOMB,1,1,1,"uuid-player",2,3000));
        var collision=new CollisionSystem();collision.setEntities(state.getEntities());
        Player p=state.getPlayer(1);collision.move(p,state.getMap(),40,0);
        check(p.getX()==72,"exit bomb tile");collision.move(p,state.getMap(),-40,0);
        check(p.getX()==64,"cannot reenter bomb");
        state.removeEntity("BOMB:1");collision.setEntities(state.getEntities());collision.move(p,state.getMap(),-32,0);
        check(p.getX()==32,"walk after bomb removed");
    }
    /** Harness chỉ nối socket trong test; runtime thật không sở hữu broadcaster hoặc socket. */
    private static void runtimeAndTcp() throws Exception {
        try(var listener=new ServerSocket(0);var manager=new GameRuntimeManager();var client=new ClientNetwork()) {
            client.connect("127.0.0.1",listener.getLocalPort());
            try(var server=listener.accept()) {
                server.setSoTimeout(3000);
                var session=new Session("uuid-1","token","one",server);
                session.setRoomId("room-a");session.setState(SessionState.PLAYING);
                var runtime=manager.startGame("room-a",List.of(new PlayerSpawn("uuid-1",1,32,32,24,24,100)));
                var handler=new MovementHandler(manager,key -> key.equals("uuid-1")?session:null);
                client.send(MovePacketCodec.encode(new MovePacketCodec.Input(1,Direction.RIGHT,true)));
                handler.handle("uuid-1",PacketDecoder.decode(server.getInputStream()));
                handler.handle("uuid-1",MovePacketCodec.encode(new MovePacketCodec.Input(1,Direction.RIGHT,true)));
                var replica=new ClientWorldState();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<deadline) {
                    var update=runtime.pollUpdate(200,TimeUnit.MILLISECONDS);if(update==null)continue;
                    PacketEncoder.encode(SnapshotPacketCodec.encode(update),server.getOutputStream());
                    Packet packet=client.receive(Duration.ofSeconds(2));check(packet!=null,"TCP snapshot received");
                    replica.apply(SnapshotPacketCodec.decode(packet));
                    var player=replica.getPlayers().get(1);
                    if(player!=null && player.getLastProcessedInput()==1)break;
                }
                check(replica.getPlayers().get(1).getX()==37,"TCP authoritative fixed step");
                runtime.requestFullState();
                GameStateUpdate refresh=null;
                while(System.nanoTime()<deadline) {
                    var u=runtime.pollUpdate(200,TimeUnit.MILLISECONDS);
                    if(u!=null && u.isFullState()){refresh=u;break;}
                }
                check(refresh!=null && refresh.getPlayers().get(0).getX()==37,"no runaway movement");
                var other=manager.startGame("room-b",List.of(new PlayerSpawn("uuid-2",1,32,32,24,24,100)));
                var otherFull=other.pollUpdate(2,TimeUnit.SECONDS);
                check(otherFull!=null && otherFull.getPlayers().get(0).getX()==32,"room isolation");
                session.setState(SessionState.IN_ROOM);
                try {handler.handle("uuid-1",MovePacketCodec.encode(new MovePacketCodec.Input(2,Direction.RIGHT,true)));
                    throw new AssertionError("non-playing accepted");}catch(IllegalStateException expected) { }
                check(runtime.failure()==null,"runtime healthy");
                manager.stopGame("room-a");manager.stopGame("room-b");
            }
        }
    }
}

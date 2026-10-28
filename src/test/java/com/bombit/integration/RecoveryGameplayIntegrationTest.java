package com.bombit.integration;

import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.network.Server;
import com.bombit.server.session.SessionState;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** TCP + gameplay thật. Chỉ clock gameplay được điều khiển; grace mạng vẫn là 15 giây thực. */
@Timeout(35)
class RecoveryGameplayIntegrationTest {
    @Test void manyInputsAndBombsSurviveThreeRecoveriesWithFullThenDelta() throws Exception {
        try(var f=new Fixture(30000,3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);
            a.plant(f);a.move(Direction.DOWN,8);a.plant(f);a.move(Direction.DOWN,8);a.plant(f);
            a.move(Direction.RIGHT,8);a.plant(f);
            assertEquals(4,a.world.getEntities().values().stream().filter(e->e.kind()==EntityData.Kind.BOMB).count());
            String id=a.id;MatchContext context=a.context;var runtime=f.game();
            for(int round=0;round<3;round++) {
                a.drop();f.awaitDisconnected(a);
                // Tạo các snapshot mới từ B, xác nhận A không bị xoá/di chuyển khi offline.
                b.move(Direction.DOWN,1);var frozen=b.world.getPlayers().get(1);
                assertNotNull(frozen);assertFalse(frozen.isMoving());
                b.move(Direction.DOWN,2);var later=b.world.getPlayers().get(1);
                assertEquals(frozen.getX(),later.getX());assertEquals(frozen.getY(),later.getY());
                long full=a.resume();b.await(w->b.views.containsKey(full));
                assertEquals(a.views.get(full),b.views.get(full),"Full và replica peer phải giống ở cùng tick");
                int deltaBefore=a.deltas;a.move(Direction.DOWN,2);
                assertTrue(a.deltas>deltaBefore,"Sau full phải ghép tiếp được delta");
                assertEquals(id,a.id);assertEquals(context,a.context);assertSame(runtime,f.game());
                assertEquals(4,a.world.getEntities().values().stream().filter(e->e.kind()==EntityData.Kind.BOMB).count());
                waitFor(()->f.server.transport().getConnectionManager().count()==2
                        &&f.server.transport().getSessionManager().snapshot().size()==2);
                assertEquals(0,f.server.transport().getSessionManager().pendingDisconnectTimers());
            }
            // Request cũ sau reconnect không được đặt lại bom; request mới vẫn có hiệu lực.
            a.send(MatchEnvelopeCodec.wrap(a.context.matchId(),BombPacketCodec.plant(1)));
            a.move(Direction.DOWN,8);a.plant(f);
            assertEquals(5,a.world.getEntities().values().stream().filter(e->e.kind()==EntityData.Kind.BOMB).count());
            assertNull(f.results.poll(100,TimeUnit.MILLISECONDS));
            System.out.println("RECOVERY_CASE multi_input_bomb cycles=3 full_replica_equal=3 delta_after_full=3 bombs=5");
        }
    }
    @Test void offlineBombDamageRespawnBlockItemAndTimerAppearInRecoveredFullWithoutRepeatingEffects() throws Exception {
        try(var f=new Fixture(30000,3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);a.plant(f);a.drop();f.awaitDisconnected(a);
            f.nanos.set(TimeUnit.SECONDS.toNanos(1));
            b.await(w->stats(w,1).lives()==2&&stats(w,1).respawns()==1&&w.getEntities().containsKey("ITEM:1"));
            assertEquals(TileType.EMPTY,b.world.getTiles().get(19));assertFalse(b.world.getEntities().containsKey("BOMB:1"));
            assertEquals(29,b.world.getMatchFrame().remainingSeconds());assertEquals(1,b.world.getBombFrame().explosions().size());
            assertEquals(1,f.events.stream().filter(ItemSpawnedEvent.class::isInstance).count());
            assertEquals(SessionState.DISCONNECTED,f.server.transport().getSessionManager().find(a.id).orElseThrow().getState());
            long full=a.resume();b.await(w->b.views.containsKey(full));
            assertEquals(a.views.get(full),b.views.get(full));assertEquals(2,stats(a.world,1).lives());
            assertEquals(1,stats(a.world,1).respawns());assertTrue(a.world.getPlayers().get(1).isAlive());
            a.move(Direction.RIGHT,8);a.await(w->!w.getEntities().containsKey("ITEM:1"));
            assertEquals(88,a.world.getPlayers().get(1).getSpeed());
            f.game().requestFullState();a.until(p->isSnapshot(p)&&a.lastUpdate.isFullState());
            assertEquals(88,a.world.getPlayers().get(1).getSpeed());assertEquals(2,stats(a.world,1).lives());
            assertEquals(1,f.events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
            assertEquals(1,f.events.stream().filter(BlockDestroyedEvent.class::isInstance).count());
            System.out.println("RECOVERY_CASE offline_gameplay lives=2 respawns=1 item_pickups=1 speed=88 timer_seconds=29");
        }
    }
    @Test void invalidTokensAndMalformedHandshakeDoNotDestroyRecoverableOwner() throws Exception {
        try(var f=new Fixture(30000,3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);a.drop();f.awaitDisconnected(a);
            for(Packet request:List.of(SessionPacketCodec.reconnectRequest(a.id,UUID.randomUUID().toString()),
                    SessionPacketCodec.reconnectRequest(UUID.randomUUID().toString(),a.token),
                    new Packet(PacketType.RECONNECT,"{\"playerId\":\""+a.id+"\"}"))) {
                try(var denied=new ClientNetwork()) {
                    denied.connect("127.0.0.1",f.server.port());denied.send(request);
                    Packet error=denied.receive(Duration.ofSeconds(2));assertNotNull(error);
                    assertEquals(PacketType.ERROR,error.getType());
                    assertTrue(Set.of("RECONNECT_REJECTED","INVALID_PACKET").contains(SessionPacketCodec.errorCode(error)));
                }
                assertEquals(SessionState.DISCONNECTED,f.server.transport().getSessionManager().find(a.id).orElseThrow().getState());
                assertEquals(1,f.server.activeMatchCount());
            }
            a.resume();assertEquals(2,a.world.getPlayers().size());
            System.out.println("RECOVERY_CASE invalid_handshakes rejected=3 valid_owner_resumed=true");
        }
    }
    @Test void bothPlayersCanDisconnectAndResumeSameRuntimeWithoutGhostSessions() throws Exception {
        try(var f=new Fixture(30000,3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);var runtime=f.game();
            a.drop();b.drop();f.awaitDisconnected(a);f.awaitDisconnected(b);
            assertEquals(1,f.server.activeMatchCount());assertTrue(runtime.isThreadAlive());
            a.resume();b.resume();a.move(Direction.DOWN,2);b.move(Direction.DOWN,2);
            assertSame(runtime,f.game());assertEquals(2,f.server.transport().getSessionManager().snapshot().size());
            assertEquals(0,f.server.transport().getSessionManager().pendingDisconnectTimers());
            System.out.println("RECOVERY_CASE all_orphan recovered_players=2 same_runtime=true");
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void matchEndingDuringGraceCannotBeResurrected(boolean eliminated) throws Exception {
        try(var f=new Fixture(5000,eliminated?1:3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);var runtime=f.game();
            if(eliminated)a.plant(f);
            a.drop();f.awaitDisconnected(a);f.nanos.set(TimeUnit.MILLISECONDS.toNanos(eliminated?1000:5000));
            Packet over=b.until(p->p.getType()==PacketType.GAME_OVER);
            var result=GameplayPacketCodec.decodeResult(MatchEnvelopeCodec.unwrap(over,b.context.matchId()));
            assertEquals(eliminated?MatchResult.Reason.LAST_ALIVE:MatchResult.Reason.TIMEOUT,result.reason());
            if(eliminated)assertEquals(2,result.winnerPlayerId());else assertNull(result.winnerPlayerId());
            waitFor(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
            assertFalse(runtime.isThreadAlive());f.rejectEndedRecovery(a);
            waitFor(()->f.server.transport().getSessionManager().find(a.id).isEmpty());
            assertEquals(0,f.server.activeMatchCount());assertEquals(0,f.server.transport().getSessionManager().pendingDisconnectTimers());
            assertTrue(f.server.transport().getSessionManager().failure().isEmpty(),"Expected denial must not be recorded as server failure");
            System.out.println("RECOVERY_CASE ended_during_grace reason="+result.reason()+" resurrected=false");
        }
    }
    @Test void realFifteenSecondGraceExpiresAbortsAndRejectsLateTokenWithCleanup() throws Exception {
        assertEquals(15,Server.RECONNECT_GRACE_SECONDS);
        try(var f=new Fixture(30000,3)) {
            var a=f.peer("A");var b=f.peer("B");f.start(a,b);var runtime=f.game();
            a.drop();f.awaitDisconnected(a);long began=System.nanoTime();
            Packet over=b.until(p->p.getType()==PacketType.GAME_OVER,Duration.ofSeconds(20));
            assertEquals(MatchResult.Reason.ABORTED,GameplayPacketCodec.decodeResult(MatchEnvelopeCodec.unwrap(over,b.context.matchId())).reason());
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
            waitFor(()->f.server.transport().getSessionManager().find(a.id).isEmpty()
                    &&f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
            assertFalse(runtime.isThreadAlive());assertEquals(0,f.server.transport().getSessionManager().pendingDisconnectTimers());
            try(var late=new ClientNetwork()) {
                late.connect("127.0.0.1",f.server.port());late.send(SessionPacketCodec.reconnectRequest(a.id,a.token));
                var error=late.receive(Duration.ofSeconds(2));assertNotNull(error);
                assertEquals("RECONNECT_REJECTED",SessionPacketCodec.errorCode(error));
            }
            b.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).players().size()==1);
            var room=f.server.transport().getRoomManager().find(f.room);
            assertEquals(b.id,room.getHostPlayerId());assertEquals(com.bombit.server.room.Room.State.WAITING,room.getState());
            System.out.println("RECOVERY_CASE real_grace configured_seconds=15 observed_wait_ms="+elapsed+" late_token_rejected=true");
        }
    }
    @Test void applicationClientAutomaticallyRecoversThreeTimesWithBombAndStableController() throws Exception {
        try(var f=new Fixture(30000,3);var a=new BombClientIntegrationTest.Client(f.server.port(),"Auto A");
            var b=new BombClientIntegrationTest.Client(f.server.port(),"Auto B")) {
            a.connection.setAutoReconnect(true);f.room=BombClientIntegrationTest.start(a,b);f.runtime=f.game();
            var controller=a.call(a.connection::movement);var context=a.call(a.connection::context);int listeners=a.connection.listenerCount();
            a.call(()->{controller.plantBomb();return null;});var first=f.results.poll(2,TimeUnit.SECONDS);
            assertNotNull(first);assertEquals(BombError.NONE,first.error());
            for(int round=1;round<=3;round++) {
                for(int n=0;n<3;n++)a.call(()->{controller.send(controller.prediction().predict(Direction.DOWN,true));return null;});
                final int expectedAck=round*3;
                waitFor(()->a.call(()->controller.world().getPlayers().get(a.connection.localId()).getLastProcessedInput()>=expectedAck));
                var session=f.server.transport().getSessionManager().find(a.connection.sessionId()).orElseThrow();
                var oldSocket=session.getSocket();session.closeSocket();final int recovered=round;
                waitFor(()->a.statuses.stream().filter(s->s.contains("Reconnected")).count()==recovered
                        &&a.call(()->!a.connection.isReconnecting()&&controller.isInputReady()));
                assertSame(controller,a.call(a.connection::movement));assertEquals(context,a.call(a.connection::context));
                assertEquals(listeners,a.connection.listenerCount());assertTrue(oldSocket.isClosed());assertNotSame(oldSocket,session.getSocket());
                assertEquals(0,f.server.transport().getSessionManager().pendingDisconnectTimers());
                waitFor(()->f.server.transport().getConnectionManager().count()==2&&f.server.transport().getSessionManager().snapshot().size()==2);
            }
            a.call(()->{controller.plantBomb();return null;});var second=f.results.poll(2,TimeUnit.SECONDS);assertNotNull(second);
            assertEquals(BombError.NONE,second.error());assertEquals(2,second.bombId());
            System.out.println("RECOVERY_CASE auto_client cycles=3 same_controller=true bomb_request_counter_continued=true");
        }
    }

    private static MatchFrame.Stats stats(ClientWorldState world,int id) {
        return world.getMatchFrame().players().stream().filter(p->p.playerId()==id).findFirst().orElseThrow();
    }
    private static boolean isSnapshot(Packet p){return p.getType()==PacketType.GAME_SNAPSHOT||p.getType()==PacketType.FULL_STATE_SYNC;}
    private interface Check {boolean test()throws Exception;}
    private static void waitFor(Check check)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end){if(check.test())return;Thread.sleep(5);}fail("Recovery condition deadline");
    }
    private record PlayerView(int id,double x,double y,double w,double h,double speed,Direction direction,boolean moving,boolean alive,long ack) { }
    private record View(long tick,int width,int height,int tileSize,GameState.MatchState matchState,
                        List<TileType> tiles,List<PlayerView> players,Map<String,EntityData> entities,MatchFrame hud,BombFrame bombs) {
        static View of(ClientWorldState w) {
            return new View(w.getTick(),w.getWidth(),w.getHeight(),w.getTileSize(),w.getMatchState(),w.getTiles(),
                    w.getPlayers().values().stream().sorted(Comparator.comparingInt(GameStateUpdate.PlayerData::getId))
                    .map(p->new PlayerView(p.getId(),p.getX(),p.getY(),p.getWidth(),p.getHeight(),p.getSpeed(),p.direction(),p.isMoving(),p.isAlive(),p.getLastProcessedInput())).toList(),w.getEntities(),w.getMatchFrame(),w.getBombFrame());
        }
    }
    private static final class Peer implements AutoCloseable {
        final Fixture f;ClientNetwork net=new ClientNetwork();volatile boolean handshaken;
        String id,token;MatchContext context;long sequence,plantId;int deltas;
        final ClientWorldState world=new ClientWorldState();final Map<Long,View> views=new LinkedHashMap<>();
        GameStateUpdate lastUpdate;
        Peer(Fixture f,String name)throws Exception {
            this.f=f;net.connect("127.0.0.1",f.server.port());send(SessionPacketCodec.connectRequest(name));
            var ack=SessionPacketCodec.decodeAcknowledgement(until(p->p.getType()==PacketType.CONNECT_ACK));id=ack.playerId();token=ack.reconnectToken();handshaken=true;
        }
        void send(Packet p)throws IOException{net.send(p);}
        void command(short type,Map<String,?> values)throws IOException{send(LobbyPacketCodec.packet(type,values));}
        void process(Packet p)throws Exception {
            if(p.getType()==PacketType.GAME_STARTED){var next=DemoPacketCodec.decodeStarted(p);if(context!=null)assertEquals(context,next);context=next;}
            if(isSnapshot(p)&&context!=null) {
                lastUpdate=SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(p,context.matchId()));
                var result=world.apply(lastUpdate);assertNotEquals(ClientWorldState.Result.NEED_FULL_STATE,result,"No baseline gap after recovery");
                if(result==ClientWorldState.Result.APPLIED){views.put(world.getTick(),View.of(world));if(!lastUpdate.isFullState())deltas++;}
            }
        }
        Packet until(Predicate<Packet> predicate)throws Exception{return until(predicate,Duration.ofSeconds(4));}
        Packet until(Predicate<Packet> predicate,Duration timeout)throws Exception {
            long end=System.nanoTime()+timeout.toNanos();
            while(System.nanoTime()<end){var p=net.receive(Duration.ofMillis(50));if(p==null)continue;process(p);if(predicate.test(p))return p;}
            throw new AssertionError("Packet/world deadline at tick="+world.getTick());
        }
        void await(Predicate<ClientWorldState> condition)throws Exception {if(!condition.test(world))until(p->condition.test(world));}
        void move(Direction direction,int count)throws Exception {
            for(int n=0;n<count;n++)send(MatchEnvelopeCodec.wrap(context.matchId(),MovePacketCodec.encode(new MovePacketCodec.Input(++sequence,direction,true))));
            int local=context.playerId(id).orElseThrow();await(w->w.getPlayers().get(local).getLastProcessedInput()>=sequence);
        }
        void plant(Fixture f)throws Exception {
            send(MatchEnvelopeCodec.wrap(context.matchId(),BombPacketCodec.plant(++plantId)));
            var result=f.results.poll(2,TimeUnit.SECONDS);assertNotNull(result);assertEquals(BombError.NONE,result.error());
            int bomb=result.bombId();await(w->w.getEntities().containsKey("BOMB:"+bomb));
        }
        void drop(){handshaken=false;net.close();}
        long resume()throws Exception {
            net=new ClientNetwork();net.connect("127.0.0.1",f.server.port());send(SessionPacketCodec.reconnectRequest(id,token));
            var ack=SessionPacketCodec.decodeAcknowledgement(until(p->p.getType()==PacketType.CONNECT_ACK));assertEquals(id,ack.playerId());token=ack.reconnectToken();handshaken=true;
            until(p->p.getType()==PacketType.GAME_STARTED);
            Packet full=until(RecoveryGameplayIntegrationTest::isSnapshot);assertEquals(PacketType.FULL_STATE_SYNC,full.getType());assertTrue(lastUpdate.isFullState());return world.getTick();
        }
        public void close(){drop();}
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();final Queue<GameEvent> events=new ConcurrentLinkedQueue<>();
        final BlockingQueue<BombCommandResult> results=new LinkedBlockingQueue<>();final List<Peer> peers=new CopyOnWriteArrayList<>();
        final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();final AtomicInteger ping=new AtomicInteger();
        final GameRuntimeManager games;final LobbyGameServer server;final Thread accept;final AtomicReference<Throwable> failure=new AtomicReference<>();
        String room;GameRuntime runtime;
        Fixture(long duration,int lives)throws Exception {
            var rules=new MatchRules(duration,1,5,8,144,8,lives,99);
            games=new GameRuntimeManager(id->{var map=new GameMap(17,15,32);
                for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
                map.setTile(2,1,TileType.BREAKABLE_BLOCK);return map;
            },(id,state,players)->new MatchGameplayExtension(state,players,new BombRules(1000,1,5),rules,
                    p->Optional.of(ItemType.SPEED_UP),events::add,results::add,nanos::get,System::currentTimeMillis));
            server=new LobbyGameServer(0,games);server.setSeamlessRecovery(true);
            accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}},"Recovery-Accept");accept.start();
            heartbeat.scheduleAtFixedRate(()->{for(Peer p:peers)if(p.handshaken&&p.net.isConnected())try{
                p.send(new Packet(PacketType.PING,"{\"pingId\":"+ping.incrementAndGet()+"}"));
            }catch(IOException expectedDuringDrop){/* Ngắt mạng là workload cố ý của fixture. */}},500,500,TimeUnit.MILLISECONDS);
        }
        Peer peer(String name)throws Exception {var peer=new Peer(this,name);peers.add(peer);return peer;}
        GameRuntime game(){return games.findGame(room).orElseThrow();}
        void start(Peer a,Peer b)throws Exception {
            a.command(PacketType.CREATE_ROOM,Map.of("roomName","Recovery matrix","maxPlayers",2));
            room=LobbyPacketCodec.decodeRoom(a.until(p->p.getType()==PacketType.ROOM_STATE)).roomId();
            b.command(PacketType.JOIN_ROOM,Map.of("roomId",room));b.until(p->p.getType()==PacketType.ROOM_STATE);
            b.command(PacketType.READY,Map.of("ready",true));
            a.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).players().stream().anyMatch(v->v.state().equals("READY")));
            a.command(PacketType.START_GAME,Map.of());a.until(p->isSnapshot(p)&&a.lastUpdate.isFullState());b.until(p->isSnapshot(p)&&b.lastUpdate.isFullState());runtime=game();
        }
        void awaitDisconnected(Peer p)throws Exception {waitFor(()->server.transport().getSessionManager().find(p.id).orElseThrow().getState()==SessionState.DISCONNECTED);}
        void rejectEndedRecovery(Peer a)throws Exception {
            try(var attempted=new ClientNetwork()) {
                attempted.connect("127.0.0.1",server.port());attempted.send(SessionPacketCodec.reconnectRequest(a.id,a.token));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(System.nanoTime()<end){var packet=attempted.receive(Duration.ofMillis(50));
                    if(packet!=null){assertNotEquals(PacketType.GAME_STARTED,packet.getType());assertFalse(isSnapshot(packet));}
                    if(!attempted.isConnected()&&attempted.getQueuedPacketCount()==0)break;
                }
                assertFalse(attempted.isConnected());
            }
        }
        public void close()throws Exception {
            heartbeat.shutdownNow();assertTrue(heartbeat.awaitTermination(2,TimeUnit.SECONDS));
            for(Peer p:peers)p.close();server.close();accept.join(2000);
            assertFalse(accept.isAlive());assertNull(failure.get());assertTrue(server.failure().isEmpty());
            assertTrue(server.transport().getConnectionFailure().isEmpty());assertTrue(server.transport().isTerminated());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());
            assertTrue(server.transport().getSessionManager().snapshot().isEmpty());assertEquals(0,server.transport().getSessionManager().pendingDisconnectTimers());
            assertEquals(0,server.transport().getConnectionManager().count());if(runtime!=null)assertFalse(runtime.isThreadAlive());
            assertTrue(server.transport().getRoomManager().snapshot().isEmpty());
        }
    }
}

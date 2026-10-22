package com.bombit.server.game.runtime;

import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BombRuntimeIntegrationTest {
    private static GameMap map() {
        var map=new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        map.setTile(2,1,TileType.BREAKABLE_BLOCK);return map;
    }
    private static List<PlayerSpawn> spawns() {return List.of(new PlayerSpawn("a",1,32,32,24,24,150),new PlayerSpawn("b",2,160,160,24,24,150));}
    @Test void realLoopPlacesBlocksMovementExplodesAndPublishesMapAliveAndEntityChanges() throws Exception {
        var nanos=new AtomicLong();var events=new ConcurrentLinkedQueue<GameEvent>();var threads=ConcurrentHashMap.<String>newKeySet();
        var results=new LinkedBlockingQueue<BombCommandResult>();
        try(var games=new GameRuntimeManager(id->map(),(room,state,ids)->new BombGameplayExtension(state,ids,new BombRules(1000,2,1),
                e->{threads.add(Thread.currentThread().getName());events.add(e);},r->{threads.add(Thread.currentThread().getName());results.add(r);},nanos::get,()->10000L))) {
            var game=games.startGame("bomb-room",spawns());var world=new ClientWorldState();applyUntil(game,world,w->w.getPlayers().size()==2);
            assertTrue(game.submit(new PlantBombCommand(1)));assertEquals(BombError.NONE,results.poll(2,TimeUnit.SECONDS).error());
            applyUntil(game,world,w->w.getEntities().containsKey("BOMB:1"));
            for(long seq=1;seq<=6;seq++) assertTrue(game.submit(new MoveCommand(1,Direction.DOWN,seq)));
            applyUntil(game,world,w->w.getPlayers().get(1).getLastProcessedInput()==6);
            assertTrue(world.getPlayers().get(1).getY()>=64,"Owner must escape the bomb tile");
            for(long seq=7;seq<=12;seq++) assertTrue(game.submit(new MoveCommand(1,Direction.UP,seq)));
            applyUntil(game,world,w->w.getPlayers().get(1).getLastProcessedInput()==12);assertEquals(64,world.getPlayers().get(1).getY(),1);
            nanos.set(1_000_000_000);applyUntil(game,world,w->w.getEntities().isEmpty()&&!w.getPlayers().get(1).isAlive());
            assertEquals(TileType.EMPTY,world.getTiles().get(1*17+2));assertEquals(1,events.stream().filter(BombExplodedEvent.class::isInstance).count());
            assertEquals(1,threads.size());assertTrue(threads.iterator().next().startsWith("GameLoop-"));
            assertTrue(game.submit(new PlantBombCommand(1)));assertEquals(BombError.PLAYER_NOT_ALIVE,results.poll(2,TimeUnit.SECONDS).error());
            games.stopGame("bomb-room");assertFalse(game.isThreadAlive());assertNull(game.failure());assertTrue(games.findGame("bomb-room").isEmpty());
        }
    }
    @Test void roomsHaveIndependentBombStorageAndReplayGetsFreshIdsAndCleanup() throws Exception {
        var worlds=new ConcurrentHashMap<String,GameState>();var events=new ConcurrentHashMap<String,ConcurrentLinkedQueue<GameEvent>>();
        try(var games=new GameRuntimeManager(id->map(),(room,state,ids)->{
            worlds.put(room,state);var sink=new ConcurrentLinkedQueue<GameEvent>();events.put(room,sink);
            return new BombGameplayExtension(state,ids,new BombRules(60000,2,1),sink::add,r->{});
        })) {
            var a=games.startGame("A",spawns());var b=games.startGame("B",spawns());var aw=new ClientWorldState();var bw=new ClientWorldState();
            applyUntil(a,aw,w->w.getPlayers().size()==2);applyUntil(b,bw,w->w.getPlayers().size()==2);
            a.submit(new PlantBombCommand(1));applyUntil(a,aw,w->w.getEntities().size()==1);
            b.requestFullState();applyUntil(b,bw,w->w.getTick()>1);assertTrue(bw.getEntities().isEmpty());assertTrue(events.get("B").isEmpty());
            games.stopGame("A");assertFalse(a.isThreadAlive());assertTrue(worlds.get("A").getEntities().isEmpty());
            var replay=games.startGame("A",spawns());assertNotEquals(a.matchId(),replay.matchId());
            var fresh=new ClientWorldState();applyUntil(replay,fresh,w->w.getPlayers().size()==2);assertTrue(fresh.getEntities().isEmpty());
            replay.submit(new PlantBombCommand(1));applyUntil(replay,fresh,w->w.getEntities().containsKey("BOMB:1"));
            games.close();assertFalse(replay.isThreadAlive());assertFalse(b.isThreadAlive());
            assertTrue(worlds.values().stream().allMatch(s->s.getEntities().isEmpty()));
            assertNull(replay.failure());assertNull(b.failure());
        }
    }
    @Test void callbackFailureStillCleansBombsOnGameThreadAndStopsRuntime() throws Exception {
        var stateRef=new AtomicReference<GameState>();var stoppedThread=new AtomicReference<String>();
        try(var games=new GameRuntimeManager(id->map(),(room,state,ids)->{
            stateRef.set(state);
            var bombs=new BombGameplayExtension(state,ids,new BombRules(60000,2,1),e->{},r->{throw new IllegalStateException("test callback failure");});
            return new GameplayExtension(){
                public void onStart(){bombs.onStart();}public void processCommand(GameCommand c){bombs.processCommand(c);}
                public void update(long now){bombs.update(now);}public void onStop(){stoppedThread.set(Thread.currentThread().getName());bombs.onStop();}
            };
        })) {
            var game=games.startGame("failure",spawns());var world=new ClientWorldState();applyUntil(game,world,w->w.getPlayers().size()==2);
            game.submit(new PlantBombCommand(1));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(game.isThreadAlive()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertFalse(game.isThreadAlive());assertNotNull(game.failure());assertEquals("test callback failure",game.failure().getMessage());
            assertTrue(stateRef.get().getEntities().isEmpty());assertTrue(stoppedThread.get().startsWith("GameLoop-"));
        }
    }
    @Test void twoTcpClientsPlantThroughOfficialProtocolAndObserveBombExplosionAndResync() throws Exception {
        var nano=new AtomicLong();var events=new LinkedBlockingQueue<GameEvent>();
        var games=new GameRuntimeManager(id->map(),(room,state,ids)->new BombGameplayExtension(state,ids,new BombRules(1000,2,1),events::add,r->{},nano::get,()->10000L));
        var server=new LobbyGameServer(0,games,new PlayerMovementRules(24,150));var serverFailure=new AtomicReference<Throwable>();
        var accept=new Thread(()->{try{server.start();}catch(Throwable e){serverFailure.set(e);}},"bomb-tcp-server");accept.start();
        try(server;var a=new ClientNetwork();var b=new ClientNetwork()) {
            a.connect("127.0.0.1",server.port());b.connect("127.0.0.1",server.port());
            a.send(SessionPacketCodec.connectRequest("A"));b.send(SessionPacketCodec.connectRequest("B"));
            String host=SessionPacketCodec.decodeAcknowledgement(take(a,PacketType.CONNECT_ACK)).playerId();take(b,PacketType.CONNECT_ACK);
            a.send(LobbyPacketCodec.packet(PacketType.CREATE_ROOM,Map.of("roomName","bomb integration","maxPlayers",2)));
            String room=LobbyPacketCodec.decodeRoom(take(a,PacketType.ROOM_STATE)).roomId();
            b.send(LobbyPacketCodec.packet(PacketType.JOIN_ROOM,Map.of("roomId",room)));take(b,PacketType.ROOM_STATE);
            b.send(LobbyPacketCodec.packet(PacketType.READY,Map.of("ready",true)));
            receiveUntil(b,p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).players().stream().anyMatch(x->x.state().equals("READY")));
            a.send(new Packet(PacketType.START_GAME,"{}"));var match=DemoPacketCodec.decodeStarted(take(a,PacketType.GAME_STARTED));take(b,PacketType.GAME_STARTED);
            var aw=new ClientWorldState();var bw=new ClientWorldState();
            tcpUntil(a,aw,match,w->w.getPlayers().size()==2);tcpUntil(b,bw,match,w->w.getPlayers().size()==2);
            var game=games.findGame(room).orElseThrow();int id=match.playerId(host).orElseThrow();
            a.send(MatchEnvelopeCodec.wrap(match.matchId(),BombPacketCodec.plant(1)));
            tcpUntil(a,aw,match,w->w.getEntities().size()==1);tcpUntil(b,bw,match,w->w.getEntities().size()==1);
            assertEquals(aw.getEntities(),bw.getEntities());assertEquals(host,aw.getEntities().get("BOMB:1").ownerId());
            assertEquals(1,BombPacketCodec.decodeNotice(MatchEnvelopeCodec.unwrap(take(a,PacketType.BOMB_PLANTED),match.matchId())).bombId());
            take(b,PacketType.BOMB_PLANTED);
            // Repeated requests must never enqueue another command, including after quota is freed.
            a.send(MatchEnvelopeCodec.wrap(match.matchId(),BombPacketCodec.plant(1)));
            nano.set(1_000_000_000);
            tcpUntil(a,aw,match,w->w.getEntities().isEmpty()&&!w.getPlayers().get(id).isAlive());
            tcpUntil(b,bw,match,w->w.getEntities().isEmpty()&&!w.getPlayers().get(id).isAlive());
            assertEquals(TileType.EMPTY,aw.getTiles().get(19));assertEquals(aw.getTiles(),bw.getTiles());
            assertEquals(aw.getBombFrame().explosions(),bw.getBombFrame().explosions());
            assertEquals(1,aw.getBombFrame().explosions().size());
            var ae=BombPacketCodec.decodeNotice(MatchEnvelopeCodec.unwrap(take(a,PacketType.BOMB_EXPLODE),match.matchId()));
            var be=BombPacketCodec.decodeNotice(MatchEnvelopeCodec.unwrap(take(b,PacketType.BOMB_EXPLODE),match.matchId()));
            assertEquals(ae,be);
            game.requestFullState();tcpUntil(a,aw,match,w->w.getTick()>bw.getTick());assertTrue(aw.getEntities().isEmpty());
            assertEquals(1,aw.getBombFrame().explosions().size(),"Full resync restores a missed active explosion");
            assertEquals(1,events.stream().filter(BombExplodedEvent.class::isInstance).count());
        } finally {
            server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(serverFailure.get());
            assertTrue(server.failure().isEmpty());assertTrue(server.transport().getConnectionFailure().isEmpty());assertEquals(0,server.snapshotWorkerCount());assertEquals(0,server.activeMatchCount());
        }
    }
    private static void applyUntil(GameRuntime game,ClientWorldState world,Predicate<ClientWorldState> done)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!done.test(world)&&System.nanoTime()<deadline) {var update=game.pollUpdate(100,TimeUnit.MILLISECONDS);if(update!=null)assertNotEquals(ClientWorldState.Result.NEED_FULL_STATE,world.apply(update));}
        assertTrue(done.test(world),"Runtime snapshot deadline");assertNull(game.failure());
    }
    private static Packet take(ClientNetwork net,short type)throws Exception {return receiveUntil(net,p->p.getType()==type);}
    private static Packet receiveUntil(ClientNetwork net,Predicate<Packet> predicate)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<deadline){Packet p=net.receive(Duration.ofMillis(100));if(p!=null&&predicate.test(p))return p;}
        fail("TCP packet deadline");return null;
    }
    private static void tcpUntil(ClientNetwork net,ClientWorldState world,MatchContext match,Predicate<ClientWorldState> done)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!done.test(world)&&System.nanoTime()<deadline) {
            Packet p=net.receive(Duration.ofMillis(100));if(p!=null&&(p.getType()==PacketType.GAME_SNAPSHOT||p.getType()==PacketType.FULL_STATE_SYNC))
                assertNotEquals(ClientWorldState.Result.NEED_FULL_STATE,world.apply(SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(p,match.matchId()))));
        }
        assertTrue(done.test(world),"TCP snapshot deadline");
    }
}

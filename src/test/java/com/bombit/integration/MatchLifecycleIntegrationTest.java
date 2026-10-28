package com.bombit.integration;

import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.item.ItemType;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MatchLifecycleIntegrationTest {
    private static final class Fixture implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();final AtomicReference<Throwable> failure=new AtomicReference<>();
        final List<GameState> states=new CopyOnWriteArrayList<>();final GameRuntimeManager games;
        final LobbyGameServer server;final Thread accept;
        Fixture(Consumer<GameState> setup)throws Exception{
            this(setup,new MatchRules(5000,1,3,4,200,25),ItemType.BOMB_UP);
        }
        Fixture(Consumer<GameState> setup,MatchRules rules,ItemType drop)throws Exception{
            this(setup,rules,drop,new PlayerMovementRules(24,150));
        }
        Fixture(Consumer<GameState> setup,MatchRules rules,ItemType drop,PlayerMovementRules movement)throws Exception{
            games=new GameRuntimeManager(id->{
                var map=new GameMap(17,15,32);
                for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
                map.setTile(2,1,TileType.BREAKABLE_BLOCK);return map;
            },(room,state,ids)->{
                setup.accept(state);states.add(state);
                return new MatchGameplayExtension(state,ids,new BombRules(1000,1,1),rules,
                        p->Optional.of(drop),e->{},r->{},nanos::get,System::currentTimeMillis);
            });
            server=new LobbyGameServer(0,games,movement);accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        }
        public void close()throws Exception{
            server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());assertTrue(server.failure().isEmpty());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());
            assertTrue(states.stream().allMatch(s->s.getEntities().isEmpty()&&s.getGameNotices().isEmpty()));
        }
    }
    private static final class Peer implements AutoCloseable {
        final ClientNetwork net=new ClientNetwork();final List<Packet> history=new ArrayList<>();
        final String session;MatchContext match;ClientWorldState world=new ClientWorldState();
        Peer(Fixture f,String name)throws Exception{
            net.connect("127.0.0.1",f.server.port());net.send(SessionPacketCodec.connectRequest(name));session=SessionPacketCodec.decodeAcknowledgement(take(PacketType.CONNECT_ACK)).playerId();
        }
        void command(short type,Map<String,?> body)throws Exception{net.send(LobbyPacketCodec.packet(type,body));}
        void scoped(Packet body)throws Exception{net.send(MatchEnvelopeCodec.wrap(match.matchId(),body));}
        Packet receive()throws Exception{
            var p=net.receive(Duration.ofMillis(100));if(p==null)return null;history.add(p);
            if(match!=null&&(p.getType()==PacketType.GAME_SNAPSHOT||p.getType()==PacketType.FULL_STATE_SYNC))
                assertNotEquals(ClientWorldState.Result.NEED_FULL_STATE,world.apply(SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(p,match.matchId()))));
            return p;
        }
        Packet take(short type)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<end){var p=receive();if(p!=null&&p.getType()==type)return p;}throw new AssertionError("Missing packet "+type);
        }
        void until(Predicate<ClientWorldState> done)throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!done.test(world)&&System.nanoTime()<end)receive();assertTrue(done.test(world),"Snapshot deadline");
        }
        void started()throws Exception{match=DemoPacketCodec.decodeStarted(take(PacketType.GAME_STARTED));world=new ClientWorldState();until(w->w.getPlayers().size()==2);}
        MatchResult ended()throws Exception{return GameplayPacketCodec.decodeResult(MatchEnvelopeCodec.unwrap(take(PacketType.GAME_OVER),match.matchId()));}
        void moves(Direction direction,long first,long last)throws Exception{for(long seq=first;seq<=last;seq++)scoped(MovePacketCodec.encode(new MovePacketCodec.Input(seq,direction,true)));until(w->w.getPlayers().get(match.playerId(session).orElseThrow()).getLastProcessedInput()==last);}
        public void close(){net.close();}
    }
    private static String start(Peer a,Peer b)throws Exception{
        a.command(PacketType.CREATE_ROOM,Map.of("roomName","Results","maxPlayers",2));String room=LobbyPacketCodec.decodeRoom(a.take(PacketType.ROOM_STATE)).roomId();
        b.command(PacketType.JOIN_ROOM,Map.of("roomId",room));b.take(PacketType.ROOM_STATE);ready(b);a.command(PacketType.START_GAME,Map.of());a.started();b.started();return room;
    }
    private static void ready(Peer p)throws Exception{
        p.command(PacketType.READY,Map.of("ready",true));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end){var packet=p.take(PacketType.ROOM_STATE);if(LobbyPacketCodec.decodeRoom(packet).players().stream().anyMatch(v->v.playerId().equals(p.session)&&v.state().equals("READY")))return;}
        fail("Ready deadline");
    }
    @Test void pickupStatsWinnerFinalSnapshotReplayAndDisconnectAreAuthoritative()throws Exception{
        try(var f=new Fixture(s->{});var a=new Peer(f,"A");var b=new Peer(f,"B")){
            f.server.setSeamlessRecovery(false); // Explicit coverage of the legacy abort policy.
            String room=start(a,b);var first=a.match.matchId();
            a.scoped(BombPacketCodec.plant(1));a.until(w->w.getEntities().containsKey("BOMB:1"));a.moves(Direction.DOWN,1,12);
            f.nanos.set(1_000_000_000L);a.until(w->w.getEntities().containsKey("ITEM:1"));
            a.moves(Direction.UP,13,24);a.moves(Direction.RIGHT,25,28);
            b.until(w->w.getMatchFrame().players().stream().anyMatch(p->p.playerId()==1&&p.bombLimit()==2));
            assertFalse(b.world.getEntities().containsKey("ITEM:1"));
            Packet pickup=b.take(PacketType.ITEM_PICKED_UP);GameplayPacketCodec.validateNotice(MatchEnvelopeCodec.unwrap(pickup,b.match.matchId()));
            b.scoped(BombPacketCodec.plant(1));b.until(w->w.getEntities().containsKey("BOMB:2"));
            f.nanos.set(2_000_000_000L);var ar=a.ended();var br=b.ended();assertEquals(ar,br);
            assertEquals(MatchResult.Reason.LAST_ALIVE,ar.reason());assertEquals(1,ar.winnerPlayerId());
            assertEquals(GameState.MatchState.FINISHED,a.world.getMatchState());assertEquals(ar,a.world.getMatchFrame().result());
            await(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
            ready(b);a.command(PacketType.START_GAME,Map.of());a.started();b.started();assertNotEquals(first,a.match.matchId());
            assertEquals(TileType.BREAKABLE_BLOCK,a.world.getTiles().get(19));assertTrue(a.world.getEntities().isEmpty());
            assertTrue(a.world.getPlayers().values().stream().allMatch(GameStateUpdate.PlayerData::isAlive));
            assertTrue(a.world.getMatchFrame().players().stream().allMatch(p->p.bombLimit()==1&&p.range()==1));
            assertNull(a.world.getMatchFrame().result());assertEquals(5,a.world.getMatchFrame().remainingSeconds());
            a.net.send(MatchEnvelopeCodec.wrap(first,BombPacketCodec.plant(999)));assertEquals("STALE_MATCH",SessionPacketCodec.errorCode(a.take(PacketType.ERROR)));
            b.close();var abort=a.ended();assertEquals(MatchResult.Reason.ABORTED,abort.reason());assertNull(abort.winnerPlayerId());
            await(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);assertTrue(f.games.findGame(room).isEmpty());
        }
    }
    @Test void simultaneousDeathsProduceOneDrawForBothClients()throws Exception{
        // Move the guest next to the host before the runtime owns state; no network thread edits gameplay.
        try(var f=new Fixture(s->s.getPlayer(2).setPosition(32,64));var a=new Peer(f,"A");var b=new Peer(f,"B")){
            start(a,b);a.scoped(BombPacketCodec.plant(1));a.until(w->w.getEntities().containsKey("BOMB:1"));
            f.nanos.set(1_000_000_000L);var ar=a.ended();assertEquals(ar,b.ended());assertEquals(MatchResult.Reason.ALL_DEAD,ar.reason());
            assertNull(ar.winnerPlayerId());assertTrue(ar.rankings().stream().allMatch(r->r.rank()==1));
            assertEquals(2,a.history.stream().filter(p->p.getType()==PacketType.PLAYER_DIE).count());
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(150);while(System.nanoTime()<end)a.receive();
            assertEquals(1,a.history.stream().filter(p->p.getType()==PacketType.GAME_OVER).count());
        }
    }
    @Test void applicationAppliesFinalSnapshotBeforeClosingControllerAndClearsResultOnReplay()throws Exception{
        try(var f=new Fixture(s->{});var a=new BombClientIntegrationTest.Client(f.server.port(),"App A");var b=new BombClientIntegrationTest.Client(f.server.port(),"App B")){
            BombClientIntegrationTest.start(a,b);var old=a.call(a.connection::movement);var match=a.call(a.connection::context);
            f.nanos.set(5_000_000_000L);
            await(()->a.call(a.connection::lastResult)!=null&&b.call(b.connection::lastResult)!=null);
            assertEquals(a.call(a.connection::lastResult),b.call(b.connection::lastResult));assertEquals(MatchResult.Reason.TIMEOUT,a.call(a.connection::lastResult).reason());
            assertEquals(GameState.MatchState.FINISHED,a.call(()->old.world().getMatchState()));
            assertTrue(a.call(old::isClosed));assertNull(a.call(a.connection::movement));assertNull(a.call(a.connection::context));
            b.command(PacketType.READY,Map.of("ready",true));
            await(()->b.call(()->b.lobby.stream().filter(p->p.getType()==PacketType.ROOM_STATE).map(LobbyPacketCodec::decodeRoom)
                    .anyMatch(r->r.players().stream().anyMatch(p->p.state().equals("READY")))));
            a.command(PacketType.START_GAME,Map.of());
            await(()->a.call(a.connection::context)!=null&&a.call(()->a.connection.movement().isInputReady()));
            assertNotEquals(match.matchId(),a.call(a.connection::context).matchId());assertNull(a.call(a.connection::lastResult));
            assertNotSame(old,a.call(a.connection::movement));assertTrue(a.call(()->a.connection.movement().world().getEntities().isEmpty()));
        }
    }
    private interface Check{boolean test()throws Exception;}
    @Test void slowerMovementAndSmallShoeUpgradeAreSharedByTwoClientsAndSurviveFullResync()throws Exception{
        try(var f=new Fixture(s->{},new MatchRules(5000,1,3,4,144,8,2,99),ItemType.SPEED_UP,new PlayerMovementRules(24,80));
            var a=new Peer(f,"Slow A");var b=new Peer(f,"Slow B")){
            start(a,b);assertEquals(80,a.world.getPlayers().get(1).getSpeed());
            a.scoped(BombPacketCodec.plant(1));a.until(w->w.getEntities().containsKey("BOMB:1"));a.moves(Direction.DOWN,1,16);
            f.nanos.set(1_000_000_000L);a.until(w->w.getEntities().containsKey("ITEM:1"));
            a.moves(Direction.UP,17,32);a.moves(Direction.RIGHT,33,40);
            a.until(w->w.getPlayers().get(1).getSpeed()==88);b.until(w->w.getPlayers().get(1).getSpeed()==88);
            assertFalse(a.world.getEntities().containsKey("ITEM:1"));
            a.scoped(ResyncPacketCodec.encode(new ResyncPacketCodec.Request(-1,ResyncPacketCodec.Reason.COLD_START)));
            var full=a.take(PacketType.FULL_STATE_SYNC);var update=SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(full,a.match.matchId()));
            assertEquals(88,update.getPlayers().stream().filter(p->p.getId()==1).findFirst().orElseThrow().getSpeed());
            assertEquals(ClientWorldState.Result.STALE,a.world.apply(update));assertEquals(88,a.world.getPlayers().get(1).getSpeed());
        }
    }
    @Test void twoTcpClientsAgreeOnShieldRespawnFullResyncAndFreshReplay()throws Exception{
        try(var f=new Fixture(s->{},new MatchRules(5000,1,3,4,200,25,2,99),ItemType.SHIELD);
            var a=new Peer(f,"Shield A");var b=new Peer(f,"Shield B")){
            start(a,b);assertTrue(a.world.getEntities().isEmpty());
            assertTrue(a.world.getMatchFrame().players().stream().allMatch(p->p.lives()==2&&!p.shield()));
            a.scoped(BombPacketCodec.plant(1));a.until(w->w.getEntities().containsKey("BOMB:1"));a.moves(Direction.DOWN,1,12);
            f.nanos.set(1_000_000_000L);a.until(w->w.getEntities().containsKey("ITEM:1"));
            a.moves(Direction.UP,13,24);a.moves(Direction.RIGHT,25,28);
            a.until(w->stats(w,1).shield());b.until(w->stats(w,1).shield());
            a.scoped(BombPacketCodec.plant(2));a.until(w->w.getEntities().containsKey("BOMB:2"));
            f.nanos.set(2_000_000_000L);
            a.until(w->!stats(w,1).shield());b.until(w->!stats(w,1).shield());
            assertEquals(2,stats(a.world,1).lives());assertEquals(0,stats(a.world,1).respawns());
            a.scoped(BombPacketCodec.plant(3));a.until(w->w.getEntities().containsKey("BOMB:3"));
            f.nanos.set(3_000_000_000L);
            a.until(w->stats(w,1).respawns()==1);b.until(w->stats(w,1).respawns()==1);
            assertEquals(stats(a.world,1),stats(b.world,1));assertEquals(1,stats(a.world,1).lives());
            assertEquals(36,a.world.getPlayers().get(1).getX());assertEquals(36,a.world.getPlayers().get(1).getY());
            assertTrue(a.world.getPlayers().get(1).isAlive());assertNull(a.world.getMatchFrame().result());
            a.scoped(ResyncPacketCodec.encode(new ResyncPacketCodec.Request(-1,ResyncPacketCodec.Reason.COLD_START)));
            var full=a.take(PacketType.FULL_STATE_SYNC);var update=SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(full,a.match.matchId()));
            assertTrue(MatchEnvelopeCodec.unwrap(full,a.match.matchId()).getJsonPayload().contains("snapshotVersion\":5"));
            assertEquals(1,update.getMatchFrame().players().stream().filter(p->p.playerId()==1).findFirst().orElseThrow().lives());
            var before=stats(a.world,1);assertEquals(ClientWorldState.Result.STALE,a.world.apply(update));assertEquals(before,stats(a.world,1));
            a.scoped(new Packet(PacketType.END_GAME,"{}"));assertEquals(MatchResult.Reason.ABORTED,a.ended().reason());b.ended();
            ready(b);a.command(PacketType.START_GAME,Map.of());a.started();b.started();
            assertTrue(a.world.getEntities().isEmpty());assertTrue(a.world.getMatchFrame().players().stream()
                .allMatch(p->p.lives()==2&&!p.shield()&&p.respawns()==0&&p.bombLimit()==1&&p.range()==1));
        }
    }
    private static MatchFrame.Stats stats(ClientWorldState world,int id){return world.getMatchFrame().players().stream().filter(p->p.playerId()==id).findFirst().orElseThrow();}
    private static void await(Check check)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(check.test())return;Thread.sleep(10);}fail("Lifecycle deadline");
    }
}

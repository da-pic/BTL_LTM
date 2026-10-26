package com.bombit.integration;

import com.bombit.client.app.GameClientConnection;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class BombClientIntegrationTest {
    private static GameMap map() {
        var map=new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        map.setTile(2,1,TileType.BREAKABLE_BLOCK);return map;
    }
    static final class Client implements AutoCloseable {
        final ExecutorService ui=Executors.newSingleThreadExecutor();
        final Queue<Throwable> failures=new ConcurrentLinkedQueue<>();
        final BlockingQueue<Packet> lobby=new LinkedBlockingQueue<>();
        final GameClientConnection connection=new GameClientConnection(ui,c->{},s->{},failures::add);
        Client(int port,String name)throws Exception {
            connection.setLobbyListener(lobby::add);connection.connect("127.0.0.1",port,name);
        }
        <T>T call(Callable<T> action)throws Exception {return ui.submit(action).get(2,TimeUnit.SECONDS);}
        void command(short type,Map<String,?> body){connection.send(LobbyPacketCodec.packet(type,body));}
        Packet take(short type)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<end){var p=lobby.poll(100,TimeUnit.MILLISECONDS);if(p!=null&&p.getType()==type)return p;}
            throw new AssertionError("No lobby packet "+type);
        }
        public void close()throws Exception {
            connection.close();ui.shutdown();assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS));assertTrue(failures.isEmpty(),failures.toString());
        }
    }
    @Test void realApplicationControllersShareBombsFlamesAndMapThenResyncAndDeduplicateRequests()throws Exception {
        var nanos=new AtomicLong();var results=new LinkedBlockingQueue<BombCommandResult>();
        var games=new GameRuntimeManager(id->map(),(room,state,ids)->new BombGameplayExtension(state,ids,new BombRules(1000,1,1),
                e->{},results::add,nanos::get,System::currentTimeMillis));
        var server=new LobbyGameServer(0,games,new PlayerMovementRules(24,150));var failure=new AtomicReference<Throwable>();
        var accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        try(server;var a=new Client(server.port(),"A");var b=new Client(server.port(),"B")) {
            String room=start(a,b);
            var match=a.call(a.connection::context);var game=games.findGame(room).orElseThrow();
            a.call(()->{a.connection.movement().plantBomb();return null;});
            assertEquals(BombError.NONE,results.poll(3,TimeUnit.SECONDS).error());
            waitFor(()->a.call(()->a.connection.movement().bombs().state().bombs().size()==1)
                    &&b.call(()->b.connection.movement().bombs().state().bombs().size()==1));
            assertEquals(a.call(()->a.connection.movement().world().getEntities()),b.call(()->b.connection.movement().world().getEntities()));
            // Escape before advancing the fuse, using the same movement sender as the UI.
            a.call(()->{for(long seq=1;seq<=12;seq++)a.connection.movement().send(new MovePacketCodec.Input(seq,Direction.DOWN,true));return null;});
            waitFor(()->a.call(()->a.connection.movement().world().getPlayers().get(a.connection.localId()).getLastProcessedInput()==12));
            nanos.set(1_000_000_000L);
            waitFor(()->a.call(()->a.connection.movement().bombs().state().explosions().size()==1)
                    &&b.call(()->b.connection.movement().bombs().state().explosions().size()==1));
            assertEquals(a.call(()->a.connection.movement().bombs().state().explosions().iterator().next().affectedCells()),
                    b.call(()->b.connection.movement().bombs().state().explosions().iterator().next().affectedCells()));
            assertEquals(TileType.EMPTY,a.call(()->a.connection.movement().world().getTiles().get(19)));
            assertEquals(a.call(()->a.connection.movement().world().getTiles()),b.call(()->b.connection.movement().world().getTiles()));
            assertTrue(a.call(()->a.connection.movement().world().getPlayers().get(a.connection.localId()).isAlive()));
            long before=a.call(()->a.connection.movement().world().getTick());
            a.call(()->{a.connection.requestFullState();return null;});
            waitFor(()->a.call(()->a.connection.movement().world().getTick()>before));
            assertTrue(a.call(()->a.connection.movement().bombs().state().bombs().isEmpty()));
            assertEquals(1,a.call(()->a.connection.movement().bombs().state().explosions().size()));
            // Resend the old request after its bomb is gone, then send a fresh one. Only fresh reaches the loop.
            a.connection.send(MatchEnvelopeCodec.wrap(match.matchId(),BombPacketCodec.plant(1)));
            a.connection.send(MatchEnvelopeCodec.wrap(match.matchId(),BombPacketCodec.plant(2)));
            assertEquals(BombError.NONE,results.poll(3,TimeUnit.SECONDS).error());
            waitFor(()->a.call(()->a.connection.movement().world().getEntities().containsKey("BOMB:2")));
            assertNull(results.poll(150,TimeUnit.MILLISECONDS),"Duplicate must not enqueue a command");
            a.connection.send(MatchEnvelopeCodec.wrap(UUID.randomUUID(),BombPacketCodec.plant(3)));
            assertEquals("STALE_MATCH",SessionPacketCodec.errorCode(a.take(PacketType.ERROR)));
            a.connection.send(MatchEnvelopeCodec.wrap(match.matchId(),new Packet(PacketType.PLANT_BOMB,"{\"requestId\":3,\"playerId\":2}")));
            assertEquals("INVALID_PACKET",SessionPacketCodec.errorCode(a.take(PacketType.ERROR)));
            for(long id=3;id<=9;id++)a.connection.send(MatchEnvelopeCodec.wrap(match.matchId(),BombPacketCodec.plant(id)));
            assertEquals("RATE_LIMITED",SessionPacketCodec.errorCode(a.take(PacketType.ERROR)));
            assertNull(game.failure());
        }finally {
            server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());assertTrue(server.failure().isEmpty());
        }
    }
    @Test void defaultLobbyBootstrapEnablesBombsWithoutAnInjectedRuntime()throws Exception {
        var server=new LobbyGameServer(0);var failure=new AtomicReference<Throwable>();
        var accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        try(server;var a=new Client(server.port(),"Default A");var b=new Client(server.port(),"Default B")) {
            start(a,b);assertTrue(a.call(()->a.connection.movement().bombs().enabled()));
            assertTrue(b.call(()->b.connection.movement().bombs().enabled()));
            for(var client:List.of(a,b))client.call(()->{
                for(var p:client.connection.movement().world().getPlayers().values()){
                    assertEquals(24,p.getWidth());assertEquals(24,p.getHeight());assertEquals(80,p.getSpeed());
                    assertEquals(16,(p.getX()+p.getWidth()/2)%32);assertEquals(16,(p.getY()+p.getHeight()/2)%32);
                }
                return null;
            });
            a.call(()->{a.connection.movement().plantBomb();return null;});
            waitFor(()->a.call(()->a.connection.movement().bombs().state().bombs().size()==1)
                    &&b.call(()->b.connection.movement().bombs().state().bombs().size()==1));
        }finally {
            server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());
        }
    }
    static String start(Client a,Client b)throws Exception {
        a.command(PacketType.CREATE_ROOM,Map.of("roomName","Bomb clients","maxPlayers",2));
        String room=LobbyPacketCodec.decodeRoom(a.take(PacketType.ROOM_STATE)).roomId();
        b.command(PacketType.JOIN_ROOM,Map.of("roomId",room));b.take(PacketType.ROOM_STATE);
        b.command(PacketType.READY,Map.of("ready",true));
        waitFor(()->b.call(()->b.lobby.stream().filter(p->p.getType()==PacketType.ROOM_STATE)
                .map(LobbyPacketCodec::decodeRoom).anyMatch(r->r.players().stream().anyMatch(p->p.state().equals("READY")))));
        a.command(PacketType.START_GAME,Map.of());
        waitFor(()->a.call(()->a.connection.movement()!=null&&a.connection.movement().isInputReady())
                &&b.call(()->b.connection.movement()!=null&&b.connection.movement().isInputReady()));
        return room;
    }
    private interface Check {boolean test()throws Exception;}
    private static void waitFor(Check done)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end){if(done.test())return;Thread.sleep(10);}fail("Application client deadline");
    }
}

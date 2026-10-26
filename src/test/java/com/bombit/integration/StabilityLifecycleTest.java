package com.bombit.integration;

import com.bombit.common.protocol.*;
import com.bombit.common.dto.MatchResult;
import com.bombit.server.app.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.network.Server;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class StabilityLifecycleTest {
    private static final class Fixture implements AutoCloseable {
        final List<GameState> states=new CopyOnWriteArrayList<>();final GameRuntimeManager games;
        final LobbyGameServer server;final Thread accept;final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture(boolean injectFailure)throws Exception{
            var attempts=new AtomicInteger();games=new GameRuntimeManager(id->DemoGameServer.map(),(room,state,ids)->{
                states.add(state);var delegate=new MatchGameplayExtension(state,ids,new BombRules(3000,2,1),
                        new MatchRules(60000,0,5,8,300,25),p->Optional.empty(),e->{},r->{});
                if(!injectFailure||attempts.getAndIncrement()>0)return delegate;
                return new GameplayExtension(){boolean explode;
                    public void onStart(){delegate.onStart();}public void processCommand(GameCommand c){delegate.processCommand(c);if(c instanceof PlantBombCommand)explode=true;}
                    public void update(long now){delegate.update(now);if(explode)throw new IllegalStateException("injected runtime fault");}
                    public void onStop(){delegate.onStop();}
                };
            });
            server=new LobbyGameServer(0,games);accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        }
        public void close()throws Exception{server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());
            assertTrue(server.transport().isTerminated());assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());
            assertTrue(states.stream().allMatch(s->s.getEntities().isEmpty()&&s.getGameNotices().isEmpty()));}
    }
    @Test void twentyRoundsReleaseGameThreadsSendersAndWorldWhileConnectionListenersStayConstant()throws Exception{
        Set<Long> before=threads();
        try(var f=new Fixture(false);var a=new BombClientIntegrationTest.Client(f.server.port(),"Stress A");var b=new BombClientIntegrationTest.Client(f.server.port(),"Stress B")){
            String room=BombClientIntegrationTest.start(a,b);UUID previous=null;
            for(int n=0;n<20;n++){
                if(n>0)replay(a,b);
                var context=a.call(a.connection::context);assertNotEquals(previous,context.matchId());previous=context.matchId();
                var controller=a.call(a.connection::movement);var game=f.games.findGame(room).orElseThrow();
                a.call(()->{controller.plantBomb();return null;});
                await(()->a.call(()->controller.world().getEntities().size()==1));
                a.connection.send(MatchEnvelopeCodec.wrap(context.matchId(),new Packet(PacketType.END_GAME,"{}")));
                await(()->a.call(a.connection::lastResult)!=null&&b.call(b.connection::lastResult)!=null);
                assertEquals(MatchResult.Reason.ABORTED,a.call(a.connection::lastResult).reason());
                await(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0&&controller.isSenderStopped());
                assertFalse(game.isThreadAlive());assertNull(game.failure());assertEquals(2,f.server.transport().getConnectionManager().count());
                assertEquals(1,a.call(a.connection::listenerCount));assertEquals(1,b.call(b.connection::listenerCount));
            }
            assertEquals(20,f.states.size());assertTrue(f.server.failure().isEmpty());
        }
        await(()->{var after=threads();after.removeAll(before);return after.isEmpty();});
    }
    @Test void runtimeFailureAbortsAndCleansMatchThenSameConnectionsCanStartAgain()throws Exception{
        try(var f=new Fixture(true);var a=new BombClientIntegrationTest.Client(f.server.port(),"Fault A");var b=new BombClientIntegrationTest.Client(f.server.port(),"Fault B")){
            String room=BombClientIntegrationTest.start(a,b);var game=f.games.findGame(room).orElseThrow();
            a.call(()->{a.connection.movement().plantBomb();return null;});
            await(()->a.call(a.connection::lastResult)!=null&&b.call(b.connection::lastResult)!=null);
            assertEquals(MatchResult.Reason.ABORTED,a.call(a.connection::lastResult).reason());
            await(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
            assertEquals("injected runtime fault",game.failure().getMessage());assertFalse(game.isThreadAlive());
            assertTrue(f.states.get(0).getEntities().isEmpty());assertTrue(f.server.failure().isPresent());
            replay(a,b);assertNotEquals(game.matchId(),a.call(a.connection::context).matchId());
            assertTrue(a.call(()->a.connection.movement().world().getEntities().isEmpty()));
        }
    }
    @Test void tenServerStartStopCyclesUnblockPartialReadersAndJoinEveryOwnedWorker()throws Exception{
        Set<Long> before=threads();
        for(int n=0;n<10;n++){
            var server=new Server(0);var error=new AtomicReference<Throwable>();
            var accept=new Thread(()->{try{server.start();}catch(Throwable e){error.set(e);}});accept.start();
            try(server;var peer=new Socket("127.0.0.1",server.getPort())){
                peer.getOutputStream().write(new byte[]{0,0});peer.getOutputStream().flush();await(()->server.getConnectionManager().count()==1);
            }
            accept.join(2000);assertFalse(accept.isAlive());assertNull(error.get());assertTrue(server.isTerminated());
            assertEquals(0,server.getConnectionManager().count());assertEquals(0,server.getSessionManager().pendingDisconnectTimers());
        }
        await(()->{var after=threads();after.removeAll(before);return after.isEmpty();});
    }
    private static void replay(BombClientIntegrationTest.Client a,BombClientIntegrationTest.Client b)throws Exception{
        b.lobby.clear();b.command(PacketType.READY,Map.of("ready",true));
        await(()->b.call(()->b.lobby.stream().filter(p->p.getType()==PacketType.ROOM_STATE).map(LobbyPacketCodec::decodeRoom)
                .anyMatch(r->r.players().stream().anyMatch(p->p.state().equals("READY")))));
        a.command(PacketType.START_GAME,Map.of());await(()->a.call(()->a.connection.movement()!=null&&a.connection.movement().isInputReady())
                &&b.call(()->b.connection.movement()!=null&&b.connection.movement().isInputReady()));
    }
    private interface Check{boolean test()throws Exception;}
    private static void await(Check done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(done.test())return;Thread.sleep(5);}fail("Stability deadline");}
    private static Set<Long> threads(){
        var result=new HashSet<Long>();
        for(var t:Thread.getAllStackTraces().keySet())if(t.isAlive()&&(t.getName().startsWith("session-writer-")||t.getName().startsWith("GameLoop-")
                ||t.getName().startsWith("Lobby-Snapshot-")||Set.of("server-client-reader","connection-watchdog","connection-cleanup","heartbeat-sweep",
                "session-expiry","chat-worker","Movement-Sender","Game-Client-Control","Client-Network-Listener","Client-Write-Watchdog").contains(t.getName())))result.add(t.getId());
        return result;
    }
}

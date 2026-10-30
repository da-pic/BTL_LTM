package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** TCP thật + controller ứng dụng, đi qua manager/Classic factory có configuration. */
@Timeout(25)
class ConfiguredLobbyIntegrationTest {
    private interface Check { boolean test()throws Exception; }
    private static void await(Check condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){if(condition.test())return;Thread.sleep(10);}
        fail("Configured lobby deadline");
    }
    private static GameMap arena() {
        var map=new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)
            if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        return map;
    }

    @Test void reconnectRetainsRuntimeAndConfigurationWhileReplayCreatesNewMatch()throws Exception {
        var maps=new AtomicInteger();var nanos=new AtomicLong();
        var results=new LinkedBlockingQueue<BombCommandResult>();
        var movement=new PlayerMovementRules(24,80);
        var common=ClassicRulesAdapter.publicRules(new BombRules(1000,1,2),
                new MatchRules(30000,.75,5,8,144,8,3,99),movement);
        var factory=new ClassicModeFactory((room,cfg)->{maps.incrementAndGet();return arena();},
                cfg->cell->Optional.empty(),event->{},results::add,nanos::get,System::currentTimeMillis);
        var games=new GameRuntimeManager(new ModeRegistry(common,factory));
        var server=new LobbyGameServer(0,games,movement);
        server.setSeamlessRecovery(true);
        var failure=new AtomicReference<Throwable>();
        var accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});
        accept.start();
        try(server;var a=new BombClientIntegrationTest.Client(server.port(),"Configured A",true);
                   var b=new BombClientIntegrationTest.Client(server.port(),"Configured B",true)){
            String room=BombClientIntegrationTest.start(a,b);
            var game=games.findGame(room).orElseThrow();
            var configuration=game.configuration().orElseThrow();
            var context=a.call(a.connection::context);
            var controller=a.call(a.connection::movement);
            var prediction=a.call(controller::prediction);
            assertEquals(GameMode.CLASSIC,configuration.gameMode());assertEquals(1,maps.get());
            a.call(()->{controller.plantBomb();return null;});
            assertEquals(BombError.NONE,Objects.requireNonNull(results.poll(3,TimeUnit.SECONDS)).error());
            for(int n=0;n<6;n++)a.call(()->{controller.send(prediction.predict(Direction.DOWN,true));return null;});
            await(()->a.call(()->controller.world().getPlayers().get(a.connection.localId()).getLastProcessedInput()>=6));
            a.connection.setAutoReconnect(true);
            server.transport().getSessionManager().find(a.connection.sessionId()).orElseThrow().closeSocket();
            await(()->a.statuses.stream().anyMatch(s->s.contains("Reconnected"))
                    &&a.call(()->!a.connection.isReconnecting()&&controller.isInputReady()));
            assertSame(game,games.findGame(room).orElseThrow());assertEquals(1,maps.get());
            assertSame(configuration,game.configuration().orElseThrow());
            assertEquals(context,a.call(a.connection::context));assertSame(controller,a.call(a.connection::movement));
            a.call(()->{controller.send(prediction.predict(Direction.DOWN,true));controller.plantBomb();return null;});
            var second=results.poll(3,TimeUnit.SECONDS);assertNotNull(second);assertEquals(2,second.bombId());
            assertEquals(BombError.NONE,second.error());
            await(()->a.call(()->controller.world().getPlayers().get(a.connection.localId()).getLastProcessedInput()>=7));

            a.connection.send(a.connection.matchCommand(LobbyPacketCodec.packet(PacketType.END_GAME,Map.of())));
            await(()->server.activeMatchCount()==0&&games.findGame(room).isEmpty());
            assertFalse(game.isThreadAlive());
            b.lobby.clear();b.command(PacketType.READY,Map.of("ready",true));
            await(()->b.lobby.stream().filter(p->p.getType()==PacketType.ROOM_STATE)
                    .map(p->BombClientIntegrationTest.roomView(b,p)).anyMatch(r->r.state().equals("WAITING")
                            &&r.players().stream().anyMatch(p->p.state().equals("READY"))));
            a.command(PacketType.START_GAME,Map.of());
            await(()->games.findGame(room).filter(next->!next.matchId().equals(game.matchId())).isPresent()
                    &&a.call(()->a.connection.context()!=null&&!a.connection.context().matchId().equals(context.matchId())
                    &&a.connection.movement().isInputReady()));
            var replay=games.findGame(room).orElseThrow();
            // NV06: config thuộc room và giữ ID khi chơi lại cùng luật; match/world vẫn mới.
            assertEquals(configuration.configurationId(),replay.configuration().orElseThrow().configurationId());
            assertEquals(2,maps.get());
            assertTrue(a.call(()->a.connection.movement().world().getEntities().isEmpty()));
            assertEquals(3,IntegrationContract.PROTOCOL_VERSION);
        } finally {
            server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());
            assertTrue(server.failure().isEmpty());
        }
    }
}

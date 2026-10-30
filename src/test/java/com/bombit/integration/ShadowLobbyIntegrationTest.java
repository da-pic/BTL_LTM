package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.mode.shadow.ShadowModeFactory;
import com.bombit.server.game.runtime.*;
import com.bombit.server.session.SessionState;
import com.bombit.client.network.ClientNetwork;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Bốn client ứng dụng thật: mode, recovery, team/view, kết quả offline, replay và forfeit. */
@Timeout(30)
class ShadowLobbyIntegrationTest {
    private interface Check{boolean done()throws Exception;}
    private static void await(Check condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end){if(condition.done())return;Thread.sleep(10);}fail("Shadow lobby deadline");}
    private static final class Fixture implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();
        final GameRuntimeManager games=new GameRuntimeManager(new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),new ClassicModeFactory(),new ShadowModeFactory(nanos::get,System::currentTimeMillis)));
        final LobbyGameServer server=new LobbyGameServer(0,games);final Thread accept;
        final List<BombClientIntegrationTest.Client> clients=new ArrayList<>();
        Fixture()throws Exception{accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            for(int i=0;i<4;i++)clients.add(new BombClientIntegrationTest.Client(server.port(),"Shadow "+(i+1),true));
            await(()->clients.stream().allMatch(c->c.connection.sessionId()!=null));}
        BombClientIntegrationTest.Client host(){return clients.getFirst();}
        String start()throws Exception{
            host().command(PacketType.CREATE_ROOM,Map.of("roomName","Shadow test","maxPlayers",4,"gameMode","SHADOW_RELAY","mapSelection","FOREST"));
            await(()->host().connection.roomState()!=null);String room=host().connection.roomState().roomId();
            for(int i=1;i<4;i++){var c=clients.get(i);c.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->c.connection.roomState()!=null);}
            readyStart();return room;
        }
        void readyStart()throws Exception{
            var actual=server.transport().getRoomManager().find(host().connection.roomState().roomId());long revision=actual.configurationRevision();
            await(()->clients.stream().allMatch(c->c.connection.roomState()!=null&&c.connection.roomState().configurationRevision()==revision));
            for(int i=1;i<clients.size();i++)clients.get(i).command(PacketType.READY,Map.of("ready",true));
            await(actual::canStartGame);host().command(PacketType.START_GAME,Map.of());
            await(()->{for(var c:clients)if(!c.call(()->c.connection.movement()!=null&&c.connection.movement().isInputReady()))return false;return true;});
        }
        void end()throws Exception{host().connection.send(host().connection.matchCommand(JsonV3.packet(PacketType.END_GAME,Map.of())));
            await(()->server.activeMatchCount()==0);for(var c:clients)await(()->c.connection.context()==null);}
        public void close()throws Exception{try{for(var c:clients)c.close();}finally{server.close();accept.join(2000);}
            assertFalse(accept.isAlive());assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());assertTrue(server.failure().isEmpty(),server.failure().toString());}
    }
    private static Packet receive(ClientNetwork network,short type)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){var p=network.receive(Duration.ofMillis(100));if(p==null)continue;if(p.getType()==type)return p;
            if(p.getType()==PacketType.ERROR)fail(SessionV3Codec.errorCode(p));}throw new AssertionError("Missing packet "+type);
    }

    @Test void fourClientsRecoverRepeatedlyThenReplayAndSwitchBackToClassic()throws Exception{
        try(var f=new Fixture()){
            String room=f.start();var host=f.host();var controller=host.call(host.connection::movement);var meta=host.connection.metadata();
            var runtime=f.games.findGame(room).orElseThrow();
            assertEquals(List.of(TeamId.A,TeamId.B,TeamId.A,TeamId.B),meta.players().stream().map(MatchStartMetadata.Member::team).toList());
            assertEquals(Set.of(1,3),host.call(()->controller.world().getPlayers().keySet()));
            host.call(()->{controller.plantScan();return null;});await(()->host.connection.lastPlantResult()!=null);
            var accepted=host.connection.lastPlantResult();assertEquals("ACCEPTED",JsonV3.object(accepted.getJsonPayload(),4096).get("status"));
            for(int n=0;n<2;n++){
                long epoch=host.connection.metadata().stream().viewEpoch();
                f.server.transport().getSessionManager().find(host.connection.sessionId()).orElseThrow().getSocket().close();
                await(()->host.connection.metadata().stream().viewEpoch()>epoch&&!host.connection.isReconnecting()&&host.call(controller::isInputReady));
                assertSame(controller,host.call(host.connection::movement));assertSame(runtime,f.games.findGame(room).orElseThrow());
                assertEquals(meta.identity(),host.connection.metadata().identity());assertEquals(meta.configuration(),host.connection.metadata().configuration());
                assertEquals(Set.of(1,3),host.call(()->controller.world().getPlayers().keySet()));
                assertTrue(runtime.latestWorldFrame().recoveryAcks().getOrDefault(1,0L)>0);
            }
            host.connection.send(host.connection.matchCommand(JsonV3.packet(PacketType.PLANT_BOMB,Map.of("requestId",1,"bombType","SCAN"))));
            await(()->host.connection.lastPlantResult()!=accepted);assertEquals(accepted.getJsonPayload(),host.connection.lastPlantResult().getJsonPayload());
            f.end();assertEquals(ShadowFrame.Reason.ABORTED,host.connection.lastShadowResult().reason());
            f.readyStart();assertNotEquals(meta.identity().matchId(),host.connection.context().matchId());
            assertEquals(meta.configuration().configurationId(),host.connection.metadata().configuration().configurationId());
            assertEquals(0L,host.call(()->host.connection.movement().shadow().scanReadyAtMillis()));
            assertTrue(host.call(()->host.connection.movement().world().getEntities().isEmpty()));f.end();
            var state=host.connection.roomState();host.command(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room,"expectedRevision",state.configurationRevision(),
                    "configuration",JsonV3.body("gameMode","CLASSIC","rulesVersion","classic-v1","presetId","CLASSIC_SERVER_DEFAULT")));
            await(()->f.clients.stream().allMatch(c->c.connection.roomState().configuration().gameMode()==GameMode.CLASSIC));
            f.readyStart();assertNull(host.call(()->host.connection.movement().shadow()));assertEquals(4,host.call(()->host.connection.movement().world().getPlayers().size()));f.end();
        }
    }

    @Test void offlinePlayerReceivesCachedTeamResultAndOldMatchMetadata()throws Exception{
        try(var f=new Fixture()){
            String room=f.start();var offline=f.clients.get(3);var meta=offline.connection.metadata();String id=offline.connection.sessionId(),token=offline.connection.reconnectToken();
            offline.connection.setAutoReconnect(false);f.server.transport().getSessionManager().find(id).orElseThrow().getSocket().close();
            await(()->offline.connection.isClosed());await(()->f.server.transport().getSessionManager().find(id).orElseThrow().getState()==SessionState.DISCONNECTED);await(()->!offline.failures.isEmpty());offline.failures.clear();
            f.nanos.set(TimeUnit.MILLISECONDS.toNanos(180000));await(()->f.games.findGame(room).isEmpty());
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));SessionV3Codec.decodeAcknowledgement(receive(net,PacketType.CONNECT_ACK));
                var restored=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));assertEquals(meta.identity(),restored.identity());assertEquals(meta.players(),restored.players());
                assertTrue(restored.stream().viewEpoch()>meta.stream().viewEpoch());
                var result=ShadowModeCodec.result(JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(receive(net,PacketType.GAME_OVER),restored.identity().matchId(),restored.stream()).getJsonPayload(),16384));
                assertEquals(ShadowFrame.Reason.TIMEOUT,result.reason());assertNull(result.winningTeam());assertEquals(0,f.server.activeMatchCount());
            }
        }
    }

    @Test void hardDropRemovesOnlyAbsentPlayerAndRejectsOldTokenWhileTeamContinues()throws Exception{
        try(var f=new Fixture()){
            String room=f.start();var absent=f.clients.get(1);String id=absent.connection.sessionId(),token=absent.connection.reconnectToken();
            absent.connection.setAutoReconnect(false);f.server.transport().getSessionManager().find(id).orElseThrow().getSocket().close();await(absent.connection::isClosed);await(()->!absent.failures.isEmpty());absent.failures.clear();
            f.server.transport().getSessionManager().hardDrop(id);
            await(()->f.host().call(()->f.host().connection.movement().shadow().publicPlayers().stream().anyMatch(p->p.playerId()==2&&p.lifeState()==2)));
            assertEquals(1,f.server.activeMatchCount());assertEquals(3,f.server.transport().getRoomManager().find(room).getPlayersSnapshot().size());
            assertEquals(TeamId.B,f.clients.get(3).connection.metadata().players().get(3).team());
            try(var net=new ClientNetwork()){net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));
                assertEquals("RECOVERY_REJECTED",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));}
            var teammate=f.clients.get(3);teammate.connection.setAutoReconnect(false);
            f.server.transport().getSessionManager().hardDrop(teammate.connection.sessionId());
            await(()->teammate.connection.isClosed()&&!teammate.failures.isEmpty());teammate.failures.clear();
            await(()->f.host().connection.lastShadowResult()!=null);
            assertEquals(ShadowFrame.Reason.TEAM_ELIMINATED,f.host().connection.lastShadowResult().reason());
            assertEquals(TeamId.A,f.host().connection.lastShadowResult().winningTeam());
        }
    }

    @Test void missingShadowCapabilityCannotJoinAndWrongTokenCannotTakeOver()throws Exception{
        try(var f=new Fixture()){
            String room=f.start();
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(JsonV3.packet(PacketType.CONNECT,JsonV3.body("playerName","old client","protocolVersion",3,"snapshotVersions",List.of(6),"features",List.of("CLASSIC_V1"))));
                receive(net,PacketType.CONNECT_ACK);
                f.end();net.send(JsonV3.packet(PacketType.JOIN_ROOM,Map.of("roomId",room)));
                assertEquals("CLIENT_CAPABILITY_MISMATCH",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));
            }
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(f.host().connection.sessionId(),UUID.randomUUID().toString()));
                assertEquals("RECOVERY_REJECTED",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));
            }
            assertFalse(f.host().connection.isClosed());
        }
    }
}

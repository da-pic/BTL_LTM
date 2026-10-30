package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.mode.paradox.ParadoxGameplayExtension;
import com.bombit.server.game.mode.shadow.ShadowModeFactory;
import com.bombit.server.game.runtime.*;
import com.bombit.server.session.SessionState;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** TCP/reader/UI worker thật; clock gameplay điều khiển riêng, grace session vẫn 30 giây thật. */
@Timeout(45)
class ParadoxRecoveryIntegrationTest {
    private interface Check{boolean done()throws Exception;}
    private static void await(Check check)throws Exception{await(check,6);}
    private static void await(Check check,int seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<end){if(check.done())return;Thread.sleep(10);}fail("Paradox lifecycle deadline");
    }
    static final class Fixture implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();
        final GameRuntimeManager games;
        final LobbyGameServer server;
        final List<BombClientIntegrationTest.Client> clients=new ArrayList<>();
        final Thread accept;
        String room;
        Fixture(int count)throws Exception{this(count,MatchFormat.INDIVIDUAL);}
        Fixture(int count,MatchFormat format)throws Exception{
            ModeRuntimeFactory paradox=new ModeRuntimeFactory(){
                public GameMode gameMode(){return GameMode.PARADOX;}
                public java.util.function.LongSupplier nanoClock(){return nanos::get;}
                public java.util.function.LongSupplier epochClock(){return ()->10000+nanos.get()/1_000_000;}
                public GameMap createMap(String id,MatchConfiguration c){return new GameMap(17,15,32);}
                public GameplayExtension createGameplay(String id,MatchConfiguration c,GameState state,Map<String,Integer> ids){
                    var game=new ParadoxGameplayExtension(state,ids,new BombRules(3000,1,1),
                            new MatchRules(180000,.75,5,8,144,8,3,99),42,e->{},r->{},nanoClock(),epochClock());
                    if(c.isTeamMatch())game.useTeams(state.getPlayers().stream().map(Player::getId).toList());return game;
                }
            };
            games=new GameRuntimeManager(new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),new ClassicModeFactory(),new ShadowModeFactory(),paradox));
            server=new LobbyGameServer(0,games);accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            for(int i=0;i<count;i++)clients.add(new BombClientIntegrationTest.Client(server.port(),"Paradox "+(i+1),true));
            await(()->clients.stream().allMatch(c->c.connection.sessionId()!=null));
            host().command(PacketType.CREATE_ROOM,Map.of("roomName","Recovery","maxPlayers",count,"gameMode","PARADOX","matchFormat",format.name()));
            await(()->host().connection.roomState()!=null);room=host().connection.roomState().roomId();
            for(int i=1;i<count;i++){var peer=clients.get(i);peer.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->peer.connection.roomState()!=null);}
            start();
        }
        BombClientIntegrationTest.Client host(){return clients.getFirst();}
        void start()throws Exception{
            var actual=server.transport().getRoomManager().find(room);long revision=actual.configurationRevision();
            await(()->clients.stream().allMatch(c->c.connection.roomState().configurationRevision()==revision));
            for(int i=1;i<clients.size();i++)clients.get(i).command(PacketType.READY,Map.of("ready",true));
            await(actual::canStartGame);host().command(PacketType.START_GAME,Map.of());
            await(()->{for(var c:clients)if(!c.call(()->c.connection.movement()!=null&&c.connection.movement().isInputReady()))return false;return true;});
        }
        void enoughHistory()throws Exception{await(()->runtime().paradoxStatus().orElseThrow().simulationTick()>=45);}
        GameRuntime runtime(){return games.findGame(room).orElseThrow();}
        void disconnect(BombClientIntegrationTest.Client client)throws Exception{
            server.transport().getSessionManager().find(client.connection.sessionId()).orElseThrow().getSocket().close();
        }
        void offline(BombClientIntegrationTest.Client client)throws Exception{
            client.connection.setAutoReconnect(false);disconnect(client);
            await(client.connection::isClosed);await(()->!client.failures.isEmpty());client.failures.clear();
            await(()->server.transport().getSessionManager().find(client.connection.sessionId()).orElseThrow().getState()==SessionState.DISCONNECTED);
        }
        void rewind(BombClientIntegrationTest.Client client,long epoch)throws Exception{
            client.call(()->{client.connection.movement().rewind();return null;});
            await(()->runtime().timelineEpoch()==epoch);
            await(()->client.connection.metadata().stream().timelineEpoch()==epoch&&client.call(()->client.connection.movement().isInputReady()));
        }
        void end()throws Exception{
            host().connection.send(host().connection.matchCommand(JsonV3.packet(PacketType.END_GAME,Map.of())));
            await(()->server.activeMatchCount()==0);for(var c:clients)await(()->c.connection.context()==null);
        }
        void mode(GameMode mode)throws Exception{
            var preset=switch(mode){case CLASSIC->MatchPreset.CLASSIC_SERVER_DEFAULT;case SHADOW_RELAY->MatchPreset.SHADOW_RELAY_V1;case PARADOX->MatchPreset.PARADOX_V1;default->throw new IllegalArgumentException();};
            host().command(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room,"expectedRevision",host().connection.roomState().configurationRevision(),
                    "configuration",JsonV3.body("gameMode",mode.name(),"rulesVersion",preset.rulesVersion(),"presetId",preset.name())));
            await(()->clients.stream().allMatch(c->c.connection.roomState().configuration().gameMode()==mode));
        }
        public void close()throws Exception{
            try{for(var c:clients)c.close();}finally{server.close();accept.join(2000);}
            assertFalse(accept.isAlive());assertEquals(0,server.snapshotWorkerCount());assertTrue(server.failure().isEmpty(),server.failure().toString());
        }
    }
    private static Packet receive(ClientNetwork net,short type)throws Exception{
        long end=System.nanoTime()+4_000_000_000L;
        while(System.nanoTime()<end){var p=net.receive(Duration.ofMillis(100));if(p!=null&&p.getType()==type)return p;
            if(p!=null&&p.getType()==PacketType.ERROR&&type!=PacketType.ERROR)fail(SessionV3Codec.errorCode(p));}
        throw new AssertionError("Missing packet "+type);
    }

    @Test void teamRewindRevivesEliminatedTeammateAndChargesSurviveReconnect()throws Exception{
        try(var f=new Fixture(4,MatchFormat.TEAM_2V2)){
            f.enoughHistory();var peer=f.clients.get(1);var controller=peer.call(peer.connection::movement);var roster=peer.connection.metadata().players();
            for(int lives=2;lives>=0;lives--){
                var old=peer.connection.lastPlantResult();peer.call(()->{controller.plantBomb();return null;});
                await(()->peer.connection.lastPlantResult()!=old);assertEquals("ACCEPTED",JsonV3.object(peer.connection.lastPlantResult().getJsonPayload(),4096).get("status"));
                f.nanos.addAndGet(3_000_000_000L);final int expected=lives;
                await(()->f.runtime().latestWorldFrame().snapshot().getMatchFrame().players().stream().anyMatch(p->p.playerId()==2&&p.lives()==expected));
                await(()->peer.call(()->controller.world().getMatchFrame().players().stream().anyMatch(p->p.playerId()==2&&p.lives()==expected)));
            }
            assertEquals(1,f.server.activeMatchCount());assertFalse(peer.call(controller::isInputReady));
            f.rewind(f.host(),1);await(()->peer.call(controller::isInputReady));
            assertEquals(3,peer.call(()->controller.world().getMatchFrame().players().stream().filter(p->p.playerId()==2).findFirst().orElseThrow().lives()));
            assertEquals(roster,peer.connection.metadata().players());assertEquals(TeamId.B,roster.get(1).team());
            f.nanos.addAndGet(3_000_000_000L);f.enoughHistory();f.rewind(peer,2);
            long view=peer.connection.metadata().stream().viewEpoch();f.disconnect(peer);
            await(()->peer.connection.metadata().stream().viewEpoch()>view&&!peer.connection.isReconnecting()&&peer.call(controller::isInputReady));
            assertSame(controller,peer.call(peer.connection::movement));assertEquals(roster,peer.connection.metadata().players());
            assertFalse(peer.call(()->controller.paradox().availableCharges().contains(1)));assertFalse(peer.call(()->controller.paradox().availableCharges().contains(2)));
            assertEquals(2,peer.connection.metadata().stream().timelineEpoch());
        }
    }

    @Test void teamFrozenPlayerReceivesDamageThenRewindKeepsFreezeAndOriginalTeam()throws Exception{
        try(var f=new Fixture(4,MatchFormat.TEAM_2V2)){
            f.enoughHistory();var peer=f.clients.get(1);var roster=peer.connection.metadata().players();String id=peer.connection.sessionId(),token=peer.connection.reconnectToken();
            peer.call(()->{peer.connection.movement().plantBomb();return null;});await(()->peer.connection.lastPlantResult()!=null);f.offline(peer);
            await(()->f.runtime().latestWorldFrame().frozen().contains(2));f.nanos.set(3_000_000_000L);
            await(()->f.runtime().latestWorldFrame().snapshot().getMatchFrame().players().stream().anyMatch(p->p.playerId()==2&&p.lives()==2));
            f.rewind(f.host(),1);assertTrue(f.runtime().latestWorldFrame().frozen().contains(2));
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));receive(net,PacketType.CONNECT_ACK);
                var meta=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));assertEquals(roster,meta.players());assertEquals(1,meta.stream().timelineEpoch());
                var full=(ParadoxSnapshotV6)SnapshotV6Codec.decodeRecipient(MatchEnvelopeV3Codec.unwrapUpdate(receive(net,PacketType.FULL_STATE_SYNC),meta.identity().matchId(),meta.stream()),meta);
                assertTrue(full.inputEnabled());assertTrue(full.paradox().availableCharges().contains(2));assertFalse(full.paradox().availableCharges().contains(1));
                assertEquals(3,full.update().getMatchFrame().players().stream().filter(p->p.playerId()==2).findFirst().orElseThrow().lives());
            }
        }
    }

    @Test void teamOfflineResultUsesCurrentTimelineAndCannotBeRewound()throws Exception{
        try(var f=new Fixture(4,MatchFormat.TEAM_2V2)){
            f.enoughHistory();var absent=f.clients.get(3);var old=absent.connection.metadata();String id=absent.connection.sessionId(),token=absent.connection.reconnectToken();
            f.offline(absent);f.rewind(f.host(),1);f.nanos.set(180_000_000_000L);await(()->f.games.findGame(f.room).isEmpty());
            await(()->f.host().connection.lastTeamResult()!=null);assertNull(f.host().connection.lastResult());
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));receive(net,PacketType.CONNECT_ACK);
                var meta=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));assertEquals(old.players(),meta.players());assertEquals(1,meta.stream().timelineEpoch());
                var result=GameplayV3Codec.teamResult(JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(receive(net,PacketType.GAME_OVER),meta.identity().matchId(),meta.stream()).getJsonPayload(),16384),GameMode.PARADOX);
                assertEquals(TeamMatchResult.Reason.TIMEOUT,result.reason());assertNull(result.winningTeam());
                net.send(MatchEnvelopeV3Codec.command(meta.identity().matchId(),1,ParadoxCodec.rewind(99)));
                assertEquals("NOT_PLAYING",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));assertTrue(f.games.findGame(f.room).isEmpty());
            }
        }
    }

    @Test void teamGraceExpiryKeepsSurvivorTeamAndCannotResurrectRemovedMember()throws Exception{
        try(var f=new Fixture(4,MatchFormat.TEAM_2V2)){
            f.enoughHistory();var absent=f.clients.get(1);String id=absent.connection.sessionId(),token=absent.connection.reconnectToken();
            var roster=f.host().connection.metadata().players();f.offline(absent);long started=System.nanoTime();f.rewind(f.host(),1);
            await(()->f.server.transport().getSessionManager().find(id).isEmpty(),33);
            assertTrue(System.nanoTime()-started>=TimeUnit.SECONDS.toNanos(29));await(()->f.runtime().latestWorldFrame().snapshot().getPlayers().size()==3);
            var room=roomView(f);
            assertEquals(List.of(1,3,4),room.players().stream().map(RoomStateView.Member::slot).toList());
            assertEquals(TeamId.B,room.players().getLast().team());
            f.nanos.set(3_000_000_000L);f.rewind(f.clients.get(2),2);
            assertEquals(3,f.runtime().latestWorldFrame().snapshot().getPlayers().size());assertEquals(roster,f.host().connection.metadata().players());
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));assertEquals("RECOVERY_REJECTED",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));
            }
            var lastB=f.clients.get(3);f.offline(lastB);f.server.transport().getSessionManager().hardDrop(lastB.connection.sessionId());
            await(()->f.host().connection.lastTeamResult()!=null);
            assertEquals(TeamId.A,f.host().connection.lastTeamResult().winningTeam());assertEquals(TeamMatchResult.Reason.TEAM_ELIMINATED,f.host().connection.lastTeamResult().reason());
            assertTrue(f.games.findGame(f.room).isEmpty());
        }
    }
    private RoomStateView roomView(Fixture f){return com.bombit.server.room.RoomManager.viewV3(f.server.transport().getRoomManager().find(f.room));}

    @Test void disconnectWithQueuedTimelineFullRecoversSameControllerThenCanRecoverAgain()throws Exception{
        try(var f=new Fixture(2)){
            f.enoughHistory();var guest=f.clients.get(1);var controller=guest.call(guest.connection::movement);
            var original=guest.connection.metadata();var runtime=f.runtime();
            var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
            guest.ui.submit(()->{blocked.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(1,TimeUnit.SECONDS));
            try{f.rewind(f.host(),1);f.disconnect(guest);}finally{release.countDown();}
            await(()->guest.connection.metadata().stream().timelineEpoch()==1&&!guest.connection.isReconnecting()&&guest.call(controller::isInputReady));
            assertSame(controller,guest.call(guest.connection::movement));assertSame(runtime,f.runtime());
            assertEquals(original.identity(),guest.connection.metadata().identity());assertEquals(original.configuration(),guest.connection.metadata().configuration());
            assertTrue(runtime.latestWorldFrame().recoveryAcks().getOrDefault(2,0L)>0);
            f.nanos.set(3_000_000_000L);f.enoughHistory();f.rewind(guest,2);
            assertFalse(guest.call(()->controller.paradox().availableCharges().contains(2)));
            long view=guest.connection.metadata().stream().viewEpoch();f.disconnect(guest);
            await(()->guest.connection.metadata().stream().viewEpoch()>view&&!guest.connection.isReconnecting()&&guest.call(controller::isInputReady));
            assertEquals(2,guest.connection.metadata().stream().timelineEpoch());assertSame(controller,guest.call(guest.connection::movement));
            assertFalse(guest.call(()->controller.paradox().availableCharges().contains(2)));assertNull(runtime.failure());
        }
    }

    @Test void offlinePlayerGetsFinalCurrentTimelineResultWithoutResurrectingRuntime()throws Exception{
        try(var f=new Fixture(2)){
            f.enoughHistory();var absent=f.clients.get(1);var old=absent.connection.metadata();String id=absent.connection.sessionId(),token=absent.connection.reconnectToken();
            f.offline(absent);f.rewind(f.host(),1);f.nanos.set(180_000_000_000L);
            await(()->f.games.findGame(f.room).isEmpty());
            long recoveredView;
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));receive(net,PacketType.CONNECT_ACK);
                var meta=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));assertEquals(1,meta.stream().timelineEpoch());assertEquals(old.identity(),meta.identity());
                recoveredView=meta.stream().viewEpoch();
                var result=GameplayV3Codec.result(JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(receive(net,PacketType.GAME_OVER),meta.identity().matchId(),meta.stream()).getJsonPayload(),16384),GameMode.PARADOX);
                assertEquals(MatchResult.Reason.TIMEOUT,result.reason());assertEquals(0,f.server.activeMatchCount());assertTrue(f.games.findGame(f.room).isEmpty());
            }
            await(()->f.server.transport().getSessionManager().find(id).orElseThrow().getState()==SessionState.DISCONNECTED);
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));receive(net,PacketType.CONNECT_ACK);
                var meta=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));
                assertTrue(meta.stream().viewEpoch()>recoveredView);assertEquals(1,meta.stream().timelineEpoch());
                var result=GameplayV3Codec.result(JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(receive(net,PacketType.GAME_OVER),meta.identity().matchId(),meta.stream()).getJsonPayload(),16384),GameMode.PARADOX);
                assertEquals(MatchResult.Reason.TIMEOUT,result.reason());assertTrue(f.games.findGame(f.room).isEmpty());
            }
        }
    }

    @Test void frozenPlayerStillTakesBombDamageAndRecoveryFullContainsCurrentStats()throws Exception{
        try(var f=new Fixture(2)){
            var peer=f.clients.get(1);var controller=peer.call(peer.connection::movement);
            peer.call(()->{controller.plantBomb();return null;});await(()->peer.connection.lastPlantResult()!=null);
            // Chặn auto-reconnect để frozen tồn tại thật suốt bước explosion, không phụ thuộc race UI.
            String id=peer.connection.sessionId(),token=peer.connection.reconnectToken();f.offline(peer);
            await(()->f.runtime().latestWorldFrame().frozen().contains(2));f.nanos.set(3_000_000_000L);
            await(()->f.runtime().latestWorldFrame().snapshot().getMatchFrame().players().stream().anyMatch(p->p.playerId()==2&&p.lives()==2));
            assertTrue(f.runtime().latestWorldFrame().frozen().contains(2));
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));receive(net,PacketType.CONNECT_ACK);
                var meta=MetadataV3Codec.decodeStarted(receive(net,PacketType.GAME_STARTED));
                var full=(ParadoxSnapshotV6)SnapshotV6Codec.decodeRecipient(MatchEnvelopeV3Codec.unwrapUpdate(
                        receive(net,PacketType.FULL_STATE_SYNC),meta.identity().matchId(),meta.stream()),meta);
                assertTrue(full.inputEnabled());
                var stats=full.update().getMatchFrame().players().stream().filter(p->p.playerId()==2).findFirst().orElseThrow();
                assertEquals(2,stats.lives());assertEquals(1,stats.respawns());
                assertTrue(f.runtime().latestWorldFrame().recoveryAcks().getOrDefault(2,0L)>0);
            }
        }
    }

    @Test void thirtySecondGraceExpiryCannotBeReversedByRewindAndOldTokenIsRejected()throws Exception{
        try(var f=new Fixture(3)){
            f.enoughHistory();var absent=f.clients.get(2);String id=absent.connection.sessionId(),token=absent.connection.reconnectToken();
            f.offline(absent);long started=System.nanoTime();f.rewind(f.host(),1);
            await(()->f.server.transport().getSessionManager().find(id).isEmpty(),33);
            assertTrue(System.nanoTime()-started>=TimeUnit.SECONDS.toNanos(29),"Production grace must not be shortened for this test");
            await(()->f.runtime().latestWorldFrame().snapshot().getPlayers().size()==2);
            f.nanos.set(3_000_000_000L);f.rewind(f.clients.get(1),2);
            assertEquals(2,f.runtime().latestWorldFrame().snapshot().getPlayers().size());
            assertFalse(f.runtime().latestWorldFrame().paradox().availableCharges().contains(3));assertEquals(1,f.server.activeMatchCount());
            try(var net=new ClientNetwork()){
                net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(id,token));
                assertEquals("RECOVERY_REJECTED",SessionV3Codec.errorCode(receive(net,PacketType.ERROR)));
            }
        }
    }

    @Test void replayResetsChargesAndTimelineAndSwitchesClassicShadowParadoxWithoutLeaks()throws Exception{
        try(var f=new Fixture(4)){
            f.enoughHistory();f.rewind(f.host(),1);var first=f.host().connection.context().matchId();f.end();
            assertEquals(MatchResult.Reason.ABORTED,f.host().connection.lastResult().reason());f.start();
            assertNotEquals(first,f.host().connection.context().matchId());
            assertEquals(0,f.host().connection.metadata().stream().timelineEpoch());
            assertEquals(Set.of(1,2,3,4),f.host().call(()->f.host().connection.movement().paradox().availableCharges()));f.end();
            for(var mode:List.of(GameMode.CLASSIC,GameMode.SHADOW_RELAY,GameMode.PARADOX)){
                f.mode(mode);f.start();assertEquals(mode,f.runtime().configuration().orElseThrow().gameMode());
                if(mode==GameMode.PARADOX)assertEquals(0,f.runtime().timelineEpoch());
                f.end();await(()->f.server.snapshotWorkerCount()==0);assertTrue(f.games.findGame(f.room).isEmpty());
            }
        }
    }
}

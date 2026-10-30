package com.bombit.integration;

import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.session.SessionState;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Wire v3 qua TCP thật: từ chối v2, room transaction, epoch và kết quả offline. */
@Timeout(25)
class CurrentLobbyProtocolTest {
    private interface Check{boolean done()throws Exception;}
    private static void await(Check check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){if(check.done())return;Thread.sleep(10);}fail("V3 deadline");}
    private static final class Fixture implements AutoCloseable {
        final AtomicLong nanos=new AtomicLong();final AtomicInteger maps=new AtomicInteger();
        final GameRuntimeManager games;final LobbyGameServer server;final Thread accept;final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture(long duration)throws Exception{
            var movement=new PlayerMovementRules(24,80);var common=ClassicRulesAdapter.publicRules(new BombRules(3000,1,2),new MatchRules(duration,.75,5,8,144,8,3,99),movement);
            var factory=new ClassicModeFactory((id,c)->{maps.incrementAndGet();return new GameMap(17,15,32);},c->cell->Optional.empty(),e->{},r->{},nanos::get,System::currentTimeMillis);
            games=new GameRuntimeManager(new ModeRegistry(common,factory));server=new LobbyGameServer(0,games,movement);
            accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        }
        public void close()throws Exception{server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());assertTrue(server.failure().isEmpty(),server.failure().toString());assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());}
    }
    private static final class Peer implements AutoCloseable {
        final ClientNetwork net=new ClientNetwork();SessionV3Codec.Ack ack;MatchStartMetadata metadata;
        Peer(Fixture f,String name)throws Exception{net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.connectRequest(name));ack=SessionV3Codec.decodeAcknowledgement(take(PacketType.CONNECT_ACK));}
        Peer(Fixture f,SessionV3Codec.Ack previous)throws Exception{net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(previous.playerId(),previous.reconnectToken()));ack=SessionV3Codec.decodeAcknowledgement(take(PacketType.CONNECT_ACK));}
        Packet take(short type)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){var p=net.receive(Duration.ofMillis(100));if(p==null)continue;if(p.getType()==type){if(type==PacketType.GAME_STARTED)metadata=MetadataV3Codec.decodeStarted(p);return p;}if(p.getType()==PacketType.ERROR)fail(SessionV3Codec.errorCode(p));}throw new AssertionError("Missing "+PacketType.nameOf(type));}
        void send(short type,Map<String,?> m)throws Exception{net.send(JsonV3.packet(type,m));}
        void scoped(short type,Map<String,?> m)throws Exception{net.send(MatchEnvelopeV3Codec.command(metadata.identity().matchId(),JsonV3.packet(type,m)));}
        ClassicSnapshotV6 snapshot(boolean full)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){var p=net.receive(Duration.ofMillis(100));if(p==null)continue;
            if(p.getType()==PacketType.ERROR)fail(SessionV3Codec.errorCode(p));if(p.getType()!=(full?PacketType.FULL_STATE_SYNC:PacketType.GAME_SNAPSHOT))continue;
            try{return SnapshotV6Codec.decode(MatchEnvelopeV3Codec.unwrapUpdate(p,metadata.identity().matchId(),metadata.stream()),metadata);}catch(ProtocolRejection stale){ }
        }throw new AssertionError("No v6 snapshot");}
        public void close(){net.close();}
    }
    private static RoomStateView create(Peer a)throws Exception{a.send(PacketType.CREATE_ROOM,Map.of("roomName","V3 room","maxPlayers",2,"gameMode","CLASSIC","mapSelection","FOREST"));return MetadataV3Codec.decodeRoom(a.take(PacketType.ROOM_STATE));}
    private static RoomStateView joinReady(Peer b,RoomStateView room)throws Exception{b.send(PacketType.JOIN_ROOM,Map.of("roomId",room.roomId()));b.take(PacketType.ROOM_STATE);
        b.send(PacketType.READY,Map.of("roomId",room.roomId(),"configurationRevision",room.configurationRevision(),"ready",true));
        RoomStateView current;do{current=MetadataV3Codec.decodeRoom(b.take(PacketType.ROOM_STATE));}while(current.players().stream().noneMatch(p->p.sessionId().equals(b.ack.playerId())&&"READY".equals(p.state())));return current;}
    private static void start(Peer a,Peer b,RoomStateView room)throws Exception{a.send(PacketType.START_GAME,Map.of("roomId",room.roomId(),"configurationRevision",room.configurationRevision()));a.take(PacketType.GAME_STARTED);b.take(PacketType.GAME_STARTED);a.snapshot(true);b.snapshot(true);}

    @Test void liveListenerRejectsOldConnectReconnectSnapshotCapabilitiesAndInvalidToken()throws Exception{
        try(var f=new Fixture(30000)){
            for(var request:List.of(SessionPacketCodec.connectRequest("old"),SessionPacketCodec.reconnectRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString()),
                    JsonV3.packet(PacketType.CONNECT,JsonV3.body("playerName","x","protocolVersion",3,"snapshotVersions",List.of(5),"features",List.of("CLASSIC_V1"))))){
                try(var net=new ClientNetwork()){net.connect("127.0.0.1",f.server.port());net.send(request);var p=net.receive(Duration.ofSeconds(2));assertNotNull(p);String code=SessionV3Codec.errorCode(p);assertTrue(Set.of("UNSUPPORTED_PROTOCOL","UNSUPPORTED_SNAPSHOT").contains(code));}
            }
            try(var net=new ClientNetwork()){net.connect("127.0.0.1",f.server.port());net.send(SessionV3Codec.reconnectRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString()));assertEquals("RECOVERY_REJECTED",SessionV3Codec.errorCode(Objects.requireNonNull(net.receive(Duration.ofSeconds(2)))));}
            await(()->f.server.transport().getSessionManager().snapshot().isEmpty());assertEquals(0,f.maps.get());
        }
    }
    @Test void roomRightsStaleReadyNoopThemeAndUnavailableModeAreValidatedWithoutMutation()throws Exception{
        try(var f=new Fixture(30000);var a=new Peer(f,"host");var b=new Peer(f,"guest")){
            var room=create(a);room=joinReady(b,room);var id=room.configuration().configurationId();
            b.send(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room.roomId(),"expectedRevision",1,"configuration",JsonV3.body("gameMode","CLASSIC","rulesVersion","classic-v1","presetId","CLASSIC_SERVER_DEFAULT")));
            assertEquals("NOT_HOST",SessionV3Codec.errorCode(b.take(PacketType.ERROR)));
            a.send(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room.roomId(),"expectedRevision",1,"configuration",JsonV3.body("gameMode","CIRCUIT","rulesVersion","circuit-v1","presetId","CIRCUIT_V1")));
            assertEquals("MODE_UNAVAILABLE",SessionV3Codec.errorCode(a.take(PacketType.ERROR)));
            a.send(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room.roomId(),"expectedRevision",1,"configuration",JsonV3.body("gameMode","CLASSIC","rulesVersion","classic-v1","presetId","CLASSIC_SERVER_DEFAULT")));
            var real=f.server.transport().getRoomManager().find(room.roomId());assertEquals(id,real.configuration().orElseThrow().configurationId());assertTrue(real.canStartGame());
            b.send(PacketType.READY,Map.of("roomId",room.roomId(),"configurationRevision",2,"ready",false));assertEquals("CONFIGURATION_STALE",SessionV3Codec.errorCode(b.take(PacketType.ERROR)));assertTrue(real.canStartGame());
            a.send(PacketType.SET_MAP_SELECTION,Map.of("roomId",room.roomId(),"mapSelection","ICE"));await(()->real.getMapSelection()==MapSelection.ICE);
            assertEquals(1,real.configurationRevision());assertTrue(real.canStartGame());assertEquals(id,real.configuration().orElseThrow().configurationId());
            start(a,b,room);assertEquals(MapTheme.ICE,a.metadata.identity().mapTheme());assertEquals(GameMode.CLASSIC,a.metadata.configuration().gameMode());
            a.send(PacketType.SET_MAP_SELECTION,Map.of("roomId",room.roomId(),"mapSelection","FIRE"));assertEquals("ROOM_PLAYING",SessionV3Codec.errorCode(a.take(PacketType.ERROR)));
        }
    }
    @Test void resyncUsesNewEpochAndPlantResultsAreAuthoritativeAndDeduplicated()throws Exception{
        try(var f=new Fixture(30000);var a=new Peer(f,"a");var b=new Peer(f,"b")){
            var room=joinReady(b,create(a));start(a,b,room);long old=a.metadata.stream().viewEpoch();
            a.scoped(PacketType.REQUEST_FULL_STATE,Map.of("viewEpoch",old,"lastAppliedTick",1,"reason","BASELINE_MISMATCH"));a.take(PacketType.GAME_STARTED);assertEquals(old+1,a.metadata.stream().viewEpoch());var full=a.snapshot(true);
            a.scoped(PacketType.MOVE,Map.of("sequence",1,"direction","DOWN","moving",true));ClassicSnapshotV6 update;do{update=a.snapshot(false);}while(update.lastProcessedInput()!=1);assertTrue(update.update().getTick()>full.update().getTick());
            a.scoped(PacketType.PLANT_BOMB,Map.of("requestId",1,"bombType","NORMAL"));var accepted=JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(a.take(PacketType.PLANT_RESULT),a.metadata.identity().matchId(),a.metadata.stream()).getJsonPayload(),16384);
            assertEquals("ACCEPTED",accepted.get("status"));assertTrue(((Long)accepted.get("processedTick"))>0);
            a.scoped(PacketType.PLANT_BOMB,Map.of("requestId",1,"bombType","NORMAL"));assertEquals(accepted,JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(a.take(PacketType.PLANT_RESULT),a.metadata.identity().matchId(),a.metadata.stream()).getJsonPayload(),16384));
            a.scoped(PacketType.PLANT_BOMB,Map.of("requestId",1,"bombType","SCAN"));assertEquals("REQUEST_ID_CONFLICT",SessionV3Codec.errorCode(a.take(PacketType.ERROR)));
            a.scoped(PacketType.PLANT_BOMB,Map.of("requestId",2,"bombType","SCAN"));var rejected=JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(a.take(PacketType.PLANT_RESULT),a.metadata.identity().matchId(),a.metadata.stream()).getJsonPayload(),16384);assertEquals("REJECTED",rejected.get("status"));assertEquals("UNSUPPORTED_ACTION",rejected.get("errorCode"));
            a.net.send(MatchEnvelopeV3Codec.command(UUID.randomUUID(),JsonV3.packet(PacketType.MOVE,Map.of("sequence",2,"direction","DOWN","moving",true))));assertEquals("STALE_MATCH",SessionV3Codec.errorCode(a.take(PacketType.ERROR)));
        }
    }
    @Test void gameFinishedOfflineReturnsMetadataAndCachedResultWithoutNewRuntime()throws Exception{
        try(var f=new Fixture(1000);var a=new Peer(f,"a");var b=new Peer(f,"b")){
            var room=joinReady(b,create(a));start(a,b,room);var old=a.metadata;var runtime=f.games.findGame(room.roomId()).orElseThrow();var ack=a.ack;a.net.disconnect();
            await(()->f.server.transport().getSessionManager().find(ack.playerId()).orElseThrow().getState()==SessionState.DISCONNECTED);
            f.nanos.set(TimeUnit.SECONDS.toNanos(1));b.take(PacketType.GAME_OVER);await(()->f.games.findGame(room.roomId()).isEmpty());
            try(var resumed=new Peer(f,ack)){resumed.take(PacketType.GAME_STARTED);assertEquals(old.identity(),resumed.metadata.identity());assertNotEquals(ack.connectionId(),resumed.ack.connectionId());
                var result=GameplayV3Codec.result(JsonV3.object(MatchEnvelopeV3Codec.unwrapUpdate(resumed.take(PacketType.GAME_OVER),old.identity().matchId(),resumed.metadata.stream()).getJsonPayload(),16384));assertEquals(MatchResult.Reason.TIMEOUT,result.reason());assertEquals(1,f.maps.get());assertFalse(runtime.isThreadAlive());}
        }
    }
}

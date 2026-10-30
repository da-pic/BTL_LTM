package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Cùng bốn client/room qua mọi tổ hợp: kiểm tra cache và controller không lọt sang lượt kế. */
@Timeout(45)
class AllMatchFormatsIntegrationTest {
    private interface Check{boolean done()throws Exception;}
    private void await(Check check)throws Exception{
        long until=System.nanoTime()+6_000_000_000L;while(System.nanoTime()<until){if(check.done())return;Thread.sleep(10);}fail("All formats deadline");
    }
    private record Selection(GameMode mode,MatchFormat format,MatchPreset preset){}
    @Test void allFiveFormatsRecoverReplayAndChangeConfigurationWithoutStateLeaks()throws Exception{
        try(var server=new LobbyGameServer(0)){
            var accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            var peers=new ArrayList<BombClientIntegrationTest.Client>();
            try{
                for(int i=0;i<4;i++)peers.add(new BombClientIntegrationTest.Client(server.port(),"Matrix "+i,true));
                await(()->peers.stream().allMatch(p->p.connection.sessionId()!=null));var host=peers.getFirst();var guest=peers.get(1);
                host.command(PacketType.CREATE_ROOM,Map.of("roomName","All formats","maxPlayers",4));await(()->host.connection.roomState()!=null);
                String room=host.connection.roomState().roomId();for(int i=1;i<4;i++){var p=peers.get(i);p.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->p.connection.roomState()!=null);}
                var selections=List.of(new Selection(GameMode.CLASSIC,MatchFormat.INDIVIDUAL,MatchPreset.CLASSIC_SERVER_DEFAULT),
                        new Selection(GameMode.CLASSIC,MatchFormat.TEAM_2V2,MatchPreset.CLASSIC_SERVER_DEFAULT),
                        new Selection(GameMode.PARADOX,MatchFormat.INDIVIDUAL,MatchPreset.PARADOX_V1),
                        new Selection(GameMode.PARADOX,MatchFormat.TEAM_2V2,MatchPreset.PARADOX_V1),
                        new Selection(GameMode.SHADOW_RELAY,MatchFormat.TEAM_2V2,MatchPreset.SHADOW_RELAY_V1),
                        new Selection(GameMode.CLASSIC,MatchFormat.INDIVIDUAL,MatchPreset.CLASSIC_SERVER_DEFAULT));
                var matchIds=new HashSet<UUID>();
                for(var selected:selections){
                    host.command(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room,"expectedRevision",host.connection.roomState().configurationRevision(),
                            "configuration",JsonV3.body("gameMode",selected.mode.name(),"rulesVersion",selected.preset.rulesVersion(),"presetId",selected.preset.name(),"matchFormat",selected.format.name())));
                    await(()->peers.stream().allMatch(p->p.connection.roomState().configuration().gameMode()==selected.mode&&p.connection.roomState().configuration().matchFormat()==selected.format));
                    var configuration=host.connection.roomState().configuration();
                    for(int replay=0;replay<2;replay++){
                        long revision=server.transport().getRoomManager().find(room).configurationRevision();await(()->peers.stream().allMatch(p->p.connection.roomState().configurationRevision()==revision));
                        for(int i=1;i<4;i++)peers.get(i).command(PacketType.READY,Map.of("ready",true));await(()->server.transport().getRoomManager().find(room).canStartGame());host.command(PacketType.START_GAME,Map.of());
                        await(()->{for(var p:peers)if(!p.call(()->p.connection.movement()!=null&&p.connection.movement().isInputReady()))return false;return true;});
                        var metadata=guest.connection.metadata();var controller=guest.call(guest.connection::movement);assertTrue(matchIds.add(metadata.identity().matchId()));
                        assertEquals(configuration.configurationId(),metadata.configuration().configurationId());assertEquals(selected.format,metadata.configuration().matchFormat());
                        assertNull(host.connection.lastResult());assertNull(host.connection.lastTeamResult());assertNull(host.connection.lastShadowResult());
                        if(selected.mode==GameMode.PARADOX){
                            assertEquals(0,metadata.stream().timelineEpoch());assertEquals(Set.of(1,2,3,4),guest.call(()->controller.paradox().availableCharges()));
                            if(replay==0){
                                await(()->guest.call(()->controller.paradox().simulationTick()>=45));guest.call(()->{controller.rewind();return null;});
                                await(()->guest.connection.metadata().stream().timelineEpoch()==1&&guest.call(controller::isInputReady));
                                assertFalse(guest.call(()->controller.paradox().availableCharges().contains(2)));
                            }
                        }else assertNull(guest.call(controller::paradox));
                        assertEquals(selected.mode==GameMode.SHADOW_RELAY,guest.call(()->controller.shadow()!=null));
                        if(replay==0){
                            var before=guest.connection.metadata();server.transport().getSessionManager().find(guest.connection.sessionId()).orElseThrow().getSocket().close();
                            await(()->guest.connection.metadata().stream().viewEpoch()>before.stream().viewEpoch()&&!guest.connection.isReconnecting()&&guest.call(controller::isInputReady));
                            assertSame(controller,guest.call(guest.connection::movement));assertEquals(before.players(),guest.connection.metadata().players());
                            assertEquals(before.configuration(),guest.connection.metadata().configuration());assertEquals(before.stream().timelineEpoch(),guest.connection.metadata().stream().timelineEpoch());
                        }
                        host.command(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room,"expectedRevision",revision,"configuration",
                                JsonV3.body("gameMode",selected.mode.name(),"rulesVersion",selected.preset.rulesVersion(),"presetId",selected.preset.name(),"matchFormat",selected.format.name())));
                        assertEquals("ROOM_PLAYING",SessionV3Codec.errorCode(host.take(PacketType.ERROR)));
                        host.connection.send(host.connection.matchCommand(JsonV3.packet(PacketType.END_GAME,Map.of())));
                        await(()->peers.stream().allMatch(p->p.connection.context()==null));await(()->server.snapshotWorkerCount()==0);
                        for(var p:peers){
                            if(selected.mode==GameMode.SHADOW_RELAY){assertNotNull(p.connection.lastShadowResult());assertNull(p.connection.lastTeamResult());}
                            else if(selected.format==MatchFormat.TEAM_2V2){assertEquals(TeamMatchResult.Reason.ABORTED,p.connection.lastTeamResult().reason());assertNull(p.connection.lastResult());}
                            else {assertEquals(MatchResult.Reason.ABORTED,p.connection.lastResult().reason());assertNull(p.connection.lastTeamResult());}
                        }
                        assertTrue(guest.call(controller::isClosed));assertEquals(0,server.activeMatchCount());
                    }
                }
                assertEquals(12,matchIds.size());assertTrue(server.failure().isEmpty(),server.failure().toString());
            }finally{for(var p:peers)p.close();server.close();accept.join(2000);assertFalse(accept.isAlive());}
        }
    }
}

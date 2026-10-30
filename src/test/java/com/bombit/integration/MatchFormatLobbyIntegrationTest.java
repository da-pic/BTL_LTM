package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.server.app.LobbyGameServer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Contract qua TCP thật: preview đội được truyền đúng nhưng chưa cho chạy luật cá nhân thay đội. */
@Timeout(20)
class MatchFormatLobbyIntegrationTest {
    /** Factory production thật, thời lượng 2 giây là cấu hình fixture tường minh. */
    @ParameterizedTest @EnumSource(value=GameMode.class,names={"CLASSIC","PARADOX"})
    void naturalTimeoutDeliversTeamDrawToAllFourClients(GameMode mode)throws Exception{
        try(var server=new LobbyGameServer(0,new com.bombit.server.game.bomb.BombRules(3000,1,1),
                new com.bombit.server.game.runtime.MatchRules(2000,0,5,8,144,8,3,99))){
            var accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            var clients=new ArrayList<BombClientIntegrationTest.Client>();
            try{
                for(int i=0;i<4;i++)clients.add(new BombClientIntegrationTest.Client(server.port(),"Timeout "+i,true));
                await(()->clients.stream().allMatch(c->c.connection.sessionId()!=null));var host=clients.getFirst();
                host.command(PacketType.CREATE_ROOM,Map.of("roomName","Team timeout","maxPlayers",4,"gameMode",mode.name(),"matchFormat","TEAM_2V2"));
                await(()->host.connection.roomState()!=null);String room=host.connection.roomState().roomId();
                for(int i=1;i<4;i++){var peer=clients.get(i);peer.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->peer.connection.roomState()!=null);}
                long revision=server.transport().getRoomManager().find(room).configurationRevision();
                await(()->clients.stream().allMatch(c->c.connection.roomState().configurationRevision()==revision));
                for(int i=1;i<4;i++)clients.get(i).command(PacketType.READY,Map.of("ready",true));
                await(()->server.transport().getRoomManager().find(room).canStartGame());host.command(PacketType.START_GAME,Map.of());
                await(()->clients.stream().allMatch(c->c.connection.lastTeamResult()!=null));
                for(var peer:clients){assertEquals(TeamMatchResult.Reason.TIMEOUT,peer.connection.lastTeamResult().reason());assertNull(peer.connection.lastTeamResult().winningTeam());assertNull(peer.connection.lastResult());}
                assertEquals(0,server.activeMatchCount());assertTrue(server.failure().isEmpty(),server.failure().toString());
            }finally{for(var peer:clients)peer.close();server.close();accept.join(2000);assertFalse(accept.isAlive());}
        }
    }
    private interface Check{boolean done()throws Exception;}
    private void await(Check check)throws Exception{
        long until=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<until){if(check.done())return;Thread.sleep(10);}fail("Format lobby deadline");
    }
    @ParameterizedTest @EnumSource(value=GameMode.class,names={"CLASSIC","PARADOX"})
    void teamRosterConfigurationGateAndSwitchBackToIndividual(GameMode mode)throws Exception{
        try(var server=new LobbyGameServer(0)){
            var accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            var clients=new ArrayList<BombClientIntegrationTest.Client>();
            try{
                for(int i=0;i<4;i++)clients.add(new BombClientIntegrationTest.Client(server.port(),"Format "+i,true));
                await(()->clients.stream().allMatch(c->c.connection.sessionId()!=null));var host=clients.getFirst();
                host.command(PacketType.CREATE_ROOM,Map.of("roomName","Team contract","maxPlayers",4,"gameMode",mode.name(),"matchFormat","TEAM_2V2"));
                await(()->host.connection.roomState()!=null);String room=host.connection.roomState().roomId();
                try(var old=new ClientNetwork()){
                    old.connect("127.0.0.1",server.port());old.send(JsonV3.packet(PacketType.CONNECT,JsonV3.body("playerName","old","protocolVersion",3,
                            "snapshotVersions",List.of(6),"features",List.of("CLASSIC_V1","PARADOX_V1"))));
                    long end=System.nanoTime()+3_000_000_000L;boolean ack=false;
                    while(!ack&&System.nanoTime()<end){var packet=old.receive(Duration.ofMillis(100));ack=packet!=null&&packet.getType()==PacketType.CONNECT_ACK;}
                    assertTrue(ack);old.send(JsonV3.packet(PacketType.JOIN_ROOM,Map.of("roomId",room)));
                    Packet error=null;while(System.nanoTime()<end){var packet=old.receive(Duration.ofMillis(100));if(packet!=null&&packet.getType()==PacketType.ERROR){error=packet;break;}}
                    assertNotNull(error);assertEquals("CLIENT_CAPABILITY_MISMATCH",SessionV3Codec.errorCode(error));
                }
                for(int i=1;i<4;i++){var peer=clients.get(i);peer.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->peer.connection.roomState()!=null);}
                long revision=server.transport().getRoomManager().find(room).configurationRevision();
                await(()->clients.stream().allMatch(c->c.connection.roomState().configurationRevision()==revision));
                assertEquals(List.of(TeamId.A,TeamId.B,TeamId.A,TeamId.B),host.connection.roomState().players().stream().map(RoomStateView.Member::team).toList());
                for(int i=1;i<4;i++)clients.get(i).command(PacketType.READY,Map.of("ready",true));
                await(()->server.transport().getRoomManager().find(room).canStartGame());host.command(PacketType.START_GAME,Map.of());
                await(()->{for(var peer:clients)if(!peer.call(()->peer.connection.movement()!=null&&peer.connection.movement().isInputReady()))return false;return true;});
                assertEquals(MatchFormat.TEAM_2V2,host.connection.metadata().configuration().matchFormat());
                host.connection.send(host.connection.matchCommand(JsonV3.packet(PacketType.END_GAME,Map.of())));
                await(()->host.connection.lastTeamResult()!=null&&clients.stream().allMatch(c->c.connection.context()==null));
                assertEquals(TeamMatchResult.Reason.ABORTED,host.connection.lastTeamResult().reason());assertNull(host.connection.lastResult());
                var preset=mode==GameMode.CLASSIC?MatchPreset.CLASSIC_SERVER_DEFAULT:MatchPreset.PARADOX_V1;
                host.command(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",room,"expectedRevision",revision,"configuration",
                        JsonV3.body("gameMode",mode.name(),"rulesVersion",preset.rulesVersion(),"presetId",preset.name(),"matchFormat","INDIVIDUAL")));
                await(()->clients.stream().allMatch(c->c.connection.roomState().configuration().matchFormat()==MatchFormat.INDIVIDUAL));
                assertTrue(host.connection.roomState().players().stream().allMatch(p->p.team()==null));
                assertTrue(host.connection.roomState().players().stream().noneMatch(p->p.state().equals("READY")));
                for(int i=1;i<4;i++)clients.get(i).command(PacketType.READY,Map.of("ready",true));
                await(()->server.transport().getRoomManager().find(room).canStartGame());host.command(PacketType.START_GAME,Map.of());
                await(()->{for(var peer:clients)if(!peer.call(()->peer.connection.movement()!=null&&peer.connection.movement().isInputReady()))return false;return true;});
                assertEquals(MatchFormat.INDIVIDUAL,host.connection.metadata().configuration().matchFormat());
                assertTrue(host.connection.metadata().players().stream().allMatch(p->p.team()==null));
                host.connection.send(host.connection.matchCommand(JsonV3.packet(PacketType.END_GAME,Map.of())));
                await(()->server.activeMatchCount()==0);assertTrue(server.failure().isEmpty(),server.failure().toString());
            }finally{for(var peer:clients)peer.close();server.close();accept.join(2000);assertFalse(accept.isAlive());}
        }
    }
}

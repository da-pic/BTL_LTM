package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.ModeRegistry;
import com.bombit.server.game.movement.StateDeltaBuilder;
import com.bombit.server.game.replication.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamMatchResultProtocolTest {
    @Test void teamResultRoundTripsSnapshotAndGameOverForBothModes()throws Exception{
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX))for(var reason:TeamMatchResult.Reason.values()){
            var config=registry.resolvePublic(mode,MatchFormat.TEAM_2V2);var state=new GameState(new GameMap(17,15,32));
            var identities=new ArrayList<MatchContext.PlayerIdentity>();var members=new ArrayList<MatchStartMetadata.Member>();
            for(int id=1;id<=4;id++){
                String session=new UUID(0,id).toString();identities.add(new MatchContext.PlayerIdentity(session,id));
                members.add(new MatchStartMetadata.Member(session,id,id,"P"+id,config.teamForSlot(id)));
                var player=new Player(id,id*32+4,36,24,24,80);
                if(reason==TeamMatchResult.Reason.BOTH_TEAMS_ELIMINATED||reason==TeamMatchResult.Reason.TEAM_ELIMINATED&&id%2==0)player.setLives(0);
                state.addPlayer(player);
            }
            state.startMatch();state.finishMatch();
            var result=new TeamMatchResult(10,reason==TeamMatchResult.Reason.TEAM_ELIMINATED?TeamId.A:null,reason);
            var stats=state.getPlayers().stream().map(p->new MatchFrame.Stats(p.getId(),1,1,p.getLives(),false,0)).toList();
            state.setMatchFrame(new MatchFrame(179,stats,null,result));state.setBombFrame(new BombFrame(11000,List.of()));
            var identity=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.CLASSIC,identities);
            var metadata=new MatchStartMetadata(identity,config,1,members,new StreamContext(1,1,0,0));
            var full=new StateDeltaBuilder().buildUpdate(state,20,true);
            var world=new WorldFrame(full,1000,10000,Set.of(),List.of(),null,null,Map.of(),mode==GameMode.PARADOX?new ParadoxFrame(0,10,Set.of(),0):null,List.of());
            RecipientStream stream=mode==GameMode.PARADOX?new ParadoxRecipientStream(metadata):new ClassicRecipientStream(metadata);
            var snapshot=stream.build(world);var packet=SnapshotV6Codec.encode(snapshot,10000,identity);
            assertEquals(result,SnapshotV6Codec.decodeRecipient(packet,metadata).update().getMatchFrame().teamResult());
            var wire=JsonV3.object(JsonV3.encode(GameplayV3Codec.teamResultBody(result,mode)),4096);
            assertFalse(wire.containsKey("winnerPlayerId"));assertEquals(result,GameplayV3Codec.teamResult(wire,mode));
            assertThrows(IllegalArgumentException.class,()->GameplayV3Codec.result(wire,mode));
            var soloMembers=members.stream().map(m->new MatchStartMetadata.Member(m.sessionId(),m.playerId(),m.slot(),m.username(),null)).toList();
            var solo=new MatchStartMetadata(identity,config.withFormat(MatchFormat.INDIVIDUAL),1,soloMembers,metadata.stream());
            assertThrows(IOException.class,()->SnapshotV6Codec.decodeRecipient(packet,solo));
        }
    }
    @Test void invalidWinnerReasonAndModeAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new TeamMatchResult(1,TeamId.A,TeamMatchResult.Reason.TIMEOUT));
        assertThrows(IllegalArgumentException.class,()->new TeamMatchResult(1,null,TeamMatchResult.Reason.TEAM_ELIMINATED));
        var result=new TeamMatchResult(1,TeamId.B,TeamMatchResult.Reason.TEAM_ELIMINATED);
        var fields=JsonV3.object(JsonV3.encode(GameplayV3Codec.teamResultBody(result,GameMode.CLASSIC)),4096);
        assertThrows(IllegalArgumentException.class,()->GameplayV3Codec.teamResult(fields,GameMode.PARADOX));
        fields.put("winningTeam","C");assertThrows(IllegalArgumentException.class,()->GameplayV3Codec.teamResult(fields,GameMode.CLASSIC));
    }
}

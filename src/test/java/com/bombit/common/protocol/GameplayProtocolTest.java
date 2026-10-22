package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.ItemType;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameplayProtocolTest {
    @Test void legacyV4StatsStillDecodeWithOneLifeAndNoShield()throws Exception{
        var update=GameStateUpdate.fullStateModel(1,1,1,32,GameState.MatchState.RUNNING,List.of(TileType.EMPTY),List.of())
            .withBombFrame(new BombFrame(1000,List.of()))
            .withMatchFrame(new MatchFrame(10,List.of(new MatchFrame.Stats(1,2,3,4,true,2)),null));
        var json=LobbyJson.object(SnapshotPacketCodec.encode(update).getJsonPayload());
        byte[] v5=Base64.getDecoder().decode((String)json.get("data"));
        // V4 stops each Stats after range; remove the nine added bytes before the result-present flag.
        byte[] v4=Arrays.copyOf(v5,v5.length-9);v4[v4.length-1]=0;
        var packet=new Packet(PacketType.GAME_SNAPSHOT,"{\"snapshotVersion\":4,\"data\":\""+Base64.getEncoder().encodeToString(v4)+"\"}");
        assertEquals(new MatchFrame.Stats(1,2,3),SnapshotPacketCodec.decode(packet).getMatchFrame().players().getFirst());
    }
    @Test void resultAndV5SnapshotRoundTripAndRejectMalformedRankings()throws Exception{
        var result=new MatchResult(8,1,MatchResult.Reason.LAST_ALIVE,List.of(new MatchResult.Ranking(1,1),new MatchResult.Ranking(2,2)));
        assertEquals(result,GameplayPacketCodec.decodeResult(GameplayPacketCodec.result(result)));
        var frame=new MatchFrame(12,List.of(new MatchFrame.Stats(1,3,4,2,true,3),new MatchFrame.Stats(2,1,2,0,false,0)),result);
        var update=GameStateUpdate.fullStateModel(8,1,1,32,GameState.MatchState.FINISHED,List.of(TileType.EMPTY),List.of())
                .withBombFrame(new BombFrame(1000,List.of())).withMatchFrame(frame);
        var packet=SnapshotPacketCodec.encodeFullSync(update);assertTrue(packet.getJsonPayload().contains("snapshotVersion\":5"));
        assertEquals(frame,SnapshotPacketCodec.decode(packet).getMatchFrame());
        var invalid=LobbyJson.object(GameplayPacketCodec.result(result).getJsonPayload());invalid.put("rankings",List.of(Map.of("playerId",1,"rank",1),Map.of("playerId",1,"rank",2)));
        assertThrows(IllegalArgumentException.class,()->GameplayPacketCodec.decodeResult(LobbyPacketCodec.packet(PacketType.GAME_OVER,invalid)));
        assertThrows(IllegalArgumentException.class,()->new MatchResult(1,null,MatchResult.Reason.LAST_ALIVE,List.of()));
    }
    @Test void officialItemAndDeathNoticesUseSingleEventSequenceAndNumericPlayerMapping(){
        String session=UUID.randomUUID().toString();var context=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),List.of(new MatchContext.PlayerIdentity(session,7)));
        var events=List.<GameEvent>of(new ItemSpawnedEvent(1000,1,ItemType.SPEED_UP,new TilePosition(1,1)),
                new ItemPickedUpEvent(1001,1,session,ItemType.SPEED_UP),new PlayerDiedEvent(1002,session,session,"EXPLOSION"));
        for(int i=0;i<events.size();i++){
            var packet=GameplayPacketCodec.notice(9,new GameNotice(i+1,events.get(i)),context);GameplayPacketCodec.validateNotice(packet);
            var body=LobbyJson.object(packet.getJsonPayload());assertEquals((long)i+1,body.get("eventId"));
            if(i>0)assertEquals(7L,body.get("playerId"));
        }
        assertThrows(IllegalArgumentException.class,()->GameplayPacketCodec.validateNotice(new Packet(PacketType.ITEM_PICKED_UP,"{}")));
    }
}

package com.bombit.common.protocol;

import com.bombit.common.dto.BombFrame;
import com.bombit.common.dto.GameNotice;
import com.bombit.common.dto.MatchContext;
import com.bombit.server.game.event.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BombProtocolTest {
    @Test void plantHasOnlyRequestIdAndMatchEnvelopeRejectsPreviousMatch() throws Exception {
        var id=UUID.randomUUID();var p=BombPacketCodec.plant(9);
        assertEquals(9,BombPacketCodec.decodePlant(MatchEnvelopeCodec.unwrap(MatchEnvelopeCodec.wrap(id,p),id)));
        assertThrows(Exception.class,()->MatchEnvelopeCodec.unwrap(MatchEnvelopeCodec.wrap(id,p),UUID.randomUUID()));
        for(String body:List.of("{}","{\"requestId\":0}","{\"requestId\":1,\"playerId\":2}",
                "{\"requestId\":1,\"requestId\":2}","{\"requestId\":\"1\"}"))
            assertThrows(IllegalArgumentException.class,()->BombPacketCodec.decodePlant(new Packet(PacketType.PLANT_BOMB,body)));
    }
    @Test void snapshotV3RoundTripsEffectsAndV2RemainsSupported() throws Exception {
        var full=GameStateUpdate.fullStateModel(1,2,2,32,GameState.MatchState.RUNNING,
                Collections.nCopies(4,TileType.EMPTY),List.of());
        assertTrue(SnapshotPacketCodec.encode(full).getJsonPayload().contains("snapshotVersion\":2"));
        var frame=new BombFrame(1000,List.of(new BombFrame.Explosion(1,900,1500,Set.of(new TilePosition(1,1)))));
        var packet=SnapshotPacketCodec.encodeFullSync(full.withBombFrame(frame));
        assertTrue(packet.getJsonPayload().contains("snapshotVersion\":3"));
        assertEquals(frame,SnapshotPacketCodec.decode(packet).getBombFrame());
        assertEquals(frame,SnapshotPacketCodec.decode(SnapshotPacketCodec.encode(full.withBombFrame(frame))).getBombFrame());
        var outside=new BombFrame(1000,List.of(new BombFrame.Explosion(1,900,1500,Set.of(new TilePosition(2,1)))));
        assertThrows(java.io.IOException.class,()->SnapshotPacketCodec.decode(SnapshotPacketCodec.encode(full.withBombFrame(outside))));
        assertThrows(java.io.IOException.class,()->SnapshotPacketCodec.decode(new Packet(packet.getType(),packet.getJsonPayload().replace("Version\":3","Version\":4"))));
    }
    @Test void noticesHaveStableBombIdentityAndStrictPayloads() {
        var session=UUID.randomUUID().toString();
        var match=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),List.of(new MatchContext.PlayerIdentity(session,1)));
        var bomb=new BombPlacedEvent(1000,4,session,new TilePosition(1,1),2000,3000,2);
        assertEquals(new BombPacketCodec.Notice(PacketType.BOMB_PLANTED,1,8,4),BombPacketCodec.decodeNotice(BombPacketCodec.encodeNotice(8,new GameNotice(1,bomb),match)));
        var e=new BombExplodedEvent(3000,4,new TilePosition(1,1),Set.of(new TilePosition(1,1),new TilePosition(2,1)));
        var packet=BombPacketCodec.encodeNotice(9,new GameNotice(2,e),match);
        assertEquals(new BombPacketCodec.Notice(PacketType.BOMB_EXPLODE,2,9,4),BombPacketCodec.decodeNotice(packet));
        var invalid=LobbyJson.object(packet.getJsonPayload());var cells=(List<?>)invalid.get("affectedCells");
        invalid.put("affectedCells",List.of(cells.get(0),cells.get(0)));
        assertThrows(IllegalArgumentException.class,()->BombPacketCodec.decodeNotice(LobbyPacketCodec.packet(PacketType.BOMB_EXPLODE,invalid)));
    }
    @Test void effectExpiryPublishesEvenWithoutOtherWorldChangesAndQueueRetryKeepsNotices() {
        var state=new GameState(new com.bombit.server.game.core.GameMap(3,3,32));var builder=new StateDeltaBuilder();
        var frame=new BombFrame(1000,List.of(new BombFrame.Explosion(1,900,1500,Set.of(new TilePosition(1,1)))));
        state.setBombFrame(frame);var first=builder.buildUpdate(state,1,true);builder.commit(first);
        state.setBombFrame(new BombFrame(1100,frame.explosions()));assertNull(builder.buildUpdate(state,2,false));
        state.setBombFrame(new BombFrame(1500,List.of()));
        var expired=builder.buildUpdate(state,3,false);assertNotNull(expired);assertTrue(expired.hasChanges());
        assertTrue(expired.getBombFrame().explosions().isEmpty());builder.commit(expired);
        var e=new BombExplodedEvent(1500,1,new TilePosition(1,1),Set.of(new TilePosition(1,1)));
        state.addGameNotice(new GameNotice(1,e));var uncommitted=builder.buildUpdate(state,4,false);
        var retry=builder.buildUpdate(state,5,false);
        assertEquals(uncommitted.getGameNotices(),retry.getGameNotices());assertEquals(3,retry.getBaseTick());
        builder.commit(retry);state.clearGameNotices();assertEquals(1,retry.getGameNotices().size());
        assertThrows(UnsupportedOperationException.class,()->retry.getGameNotices().clear());
    }
}

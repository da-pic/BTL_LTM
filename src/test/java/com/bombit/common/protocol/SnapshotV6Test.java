package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.server.game.core.*;
import com.bombit.server.game.item.ItemType;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.replication.*;
import com.bombit.server.game.runtime.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** V6 thật: round-trip/replica, enqueue commit và các buffer đối nghịch. */
class SnapshotV6Test {
    private static final class Fixture implements AutoCloseable {
        final String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        final MatchConfiguration configuration=new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).resolvePublic(GameMode.CLASSIC);
        final MatchContext identity=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.ICE,List.of(new MatchContext.PlayerIdentity(a,1),new MatchContext.PlayerIdentity(b,2)));
        final GameState state=new GameState(new GameMap(17,15,32));
        final GameplayExtension gameplay;
        Fixture(){state.addPlayer(new Player(1,36,36,24,24,80));state.addPlayer(new Player(2,484,420,24,24,80));state.startMatch();
            gameplay=new ClassicModeFactory((r,c)->new GameMap(17,15,32),c->cell->Optional.empty(),e->{},r->{},()->0,()->10000).createGameplay("fixture",configuration,state,Map.of(a,1,b,2));gameplay.onStart();}
        MatchStartMetadata metadata(int recipient,long epoch){return new MatchStartMetadata(identity,configuration,1,List.of(new MatchStartMetadata.Member(a,1,1,"A",null),new MatchStartMetadata.Member(b,2,2,"B",null)),new StreamContext(recipient,epoch,0,0));}
        WorldFrame frame(long tick,long millis,Set<Integer> frozen){return new WorldFrame(new StateDeltaBuilder().buildUpdate(state,tick,true),millis,10000,frozen,List.of());}
        public void close(){gameplay.onStop();}
    }
    private static Packet body(byte[] bytes){return JsonV3.packet(PacketType.FULL_STATE_SYNC,JsonV3.body("snapshotVersion",6,"data",Base64.getEncoder().encodeToString(bytes)));}
    private static byte[] raw(Packet packet){return Base64.getDecoder().decode((String)JsonV3.object(packet.getJsonPayload(),1048574).get("data"));}

    @Test void fullDeltaEntityRemovalAndResetApplyToARealReplica()throws Exception{
        try(var f=new Fixture()){
            var meta=f.metadata(1,1);assertEquals(meta,MetadataV3Codec.decodeStarted(MetadataV3Codec.started(meta)));
            var stream=new ClassicRecipientStream(meta);var first=stream.build(f.frame(1,0,Set.of()));var full=SnapshotV6Codec.encode(first,10000,f.identity);var decoded=SnapshotV6Codec.decode(full,meta);
            var replica=new ClientWorldState();assertEquals(ClientWorldState.Result.APPLIED,replica.apply(decoded.update()));stream.commit(first);
            var saved=f.frame(1,0,Set.of());f.state.getMap().setTile(2,1,TileType.BREAKABLE_BLOCK);
            f.state.putEntity(new EntityData(EntityData.Kind.ITEM,1,3,1,"",ItemType.SPEED_UP.ordinal(),-1));f.state.getPlayer(1).acknowledgeInput(7);
            var delta=stream.build(f.frame(2,50,Set.of()));assertFalse(delta.update().isFullState());assertEquals(1,delta.update().getBaseTick());
            var changed=SnapshotV6Codec.decode(SnapshotV6Codec.encode(delta,10000,f.identity),meta);assertEquals(ClientWorldState.Result.APPLIED,replica.apply(changed.update()));stream.commit(delta);
            assertEquals(TileType.BREAKABLE_BLOCK,replica.getTiles().get(19));assertEquals(7,replica.getPlayers().get(1).getLastProcessedInput());assertEquals(1,replica.getEntities().size());
            assertEquals(TileType.EMPTY,saved.snapshot().mapTiles().get(19));
            f.state.removeEntity("ITEM:1");var remove=stream.build(f.frame(3,100,Set.of()));assertEquals(List.of("ITEM:1"),remove.update().getRemovedEntityKeys());
            assertEquals(ClientWorldState.Result.APPLIED,replica.apply(SnapshotV6Codec.decode(SnapshotV6Codec.encode(remove,10000,f.identity),meta).update()));stream.commit(remove);assertTrue(replica.getEntities().isEmpty());
            stream.reset(3);assertNull(stream.build(f.frame(3,100,Set.of())));assertEquals(2,stream.metadata().stream().viewEpoch());var resync=stream.build(f.frame(4,150,Set.of()));assertTrue(resync.update().isFullState());
        }
    }
    @Test void failedEnqueueDoesNotAdvanceBaselineAndWrongCandidateCannotCommit()throws Exception{
        try(var f=new Fixture()){
            var stream=new ClassicRecipientStream(f.metadata(1,1));var first=stream.build(f.frame(1,0,Set.of()));stream.commit(first);
            var dropped=stream.build(f.frame(2,50,Set.of()));var later=stream.build(f.frame(3,100,Set.of()));
            assertEquals(1,later.update().getBaseTick());assertThrows(IllegalStateException.class,()->stream.commit(dropped));stream.commit(later);
            assertEquals(3,stream.build(f.frame(4,150,Set.of())).update().getBaseTick());
            stream.reset(4);assertThrows(IllegalStateException.class,()->stream.commit(later));stream.close();assertNull(stream.build(f.frame(5,200,Set.of())));
        }
    }
    @Test void ackIsOnlyForRecipientAndFrozenPlayerCannotOpenInput()throws Exception{
        try(var f=new Fixture()){
            f.state.getPlayer(1).acknowledgeInput(9);var frame=f.frame(1,0,Set.of(1));
            var a=new ClassicRecipientStream(f.metadata(1,1)).build(frame);var b=new ClassicRecipientStream(f.metadata(2,1)).build(frame);
            assertFalse(a.inputEnabled());assertTrue(b.inputEnabled());
            var decoded=SnapshotV6Codec.decode(SnapshotV6Codec.encode(b,10000,f.identity),f.metadata(2,1));
            assertEquals(0,decoded.update().getPlayers().stream().filter(p->p.getId()==1).findFirst().orElseThrow().getLastProcessedInput());
            assertEquals(0,decoded.lastProcessedInput());assertEquals(9,a.lastProcessedInput());
        }
    }
    @Test void decoderRejectsVersionTrailingCountBooleanNaNAndRosterCorruption()throws Exception{
        try(var f=new Fixture()){
            var update=new ClassicRecipientStream(f.metadata(1,1)).build(f.frame(1,0,Set.of()));byte[] good=raw(SnapshotV6Codec.encode(update,10000,f.identity));
            var variants=new ArrayList<byte[]>();
            variants.add(Arrays.copyOf(good,good.length-1));variants.add(Arrays.copyOf(good,good.length+1));
            var badKind=good.clone();badKind[0]=2;variants.add(badKind);
            var badCount=good.clone();ByteBuffer.wrap(badCount).putInt(40,Integer.MAX_VALUE);variants.add(badCount);
            // Header 40, map count+255, tile count, public count+2*6, detail count+id.
            var nan=good.clone();ByteBuffer.wrap(nan).putLong(321,Double.doubleToRawLongBits(Double.NaN));variants.add(nan);
            var bool=good.clone();bool[362]=2;variants.add(bool);
            var duplicate=good.clone();ByteBuffer.wrap(duplicate).putInt(310,1);variants.add(duplicate);
            for(var bytes:variants)assertThrows(IOException.class,()->SnapshotV6Codec.decode(body(bytes),f.metadata(1,1)));
            assertThrows(IOException.class,()->SnapshotV6Codec.decode(JsonV3.packet(PacketType.FULL_STATE_SYNC,Map.of("snapshotVersion",5,"data",Base64.getEncoder().encodeToString(good))),f.metadata(1,1)));
        }
    }
}

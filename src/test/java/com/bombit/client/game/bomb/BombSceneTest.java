package com.bombit.client.game.bomb;

import com.bombit.client.game.movement.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.BombFrame;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BombSceneTest {
    private static GameStateUpdate full(long tick) {
        return GameStateUpdate.fullStateModel(tick,3,3,32,GameState.MatchState.RUNNING,Collections.nCopies(9,TileType.EMPTY),
                List.of(new GameStateUpdate.PlayerData(1,32,32,24,24,150,Direction.DOWN,false,true,0)));
    }
    private static BombFrame frame() {
        return new BombFrame(1000,List.of(new BombFrame.Explosion(1,900,1500,Set.of(new TilePosition(1,1)))));
    }
    @Test void controllerIgnoresDuplicateOldAndFutureEventsAndResyncRestoresEffectsWithoutDamage() throws Exception {
        var resyncs=new AtomicInteger();var failures=new ArrayList<Throwable>();var match=UUID.randomUUID();
        try(var net=new ClientNetwork();var client=new MovementClientController(net,1,Runnable::run,resyncs::incrementAndGet,failures::add,match,false)) {
            var notice=MatchEnvelopeCodec.wrap(match,new Packet(PacketType.BOMB_EXPLODE,
                    "{\"eventId\":2,\"tick\":2,\"occurredAtMillis\":900,\"bombId\":1,\"x\":1,\"y\":1,\"affectedCells\":[{\"x\":1,\"y\":1}]}"));
            client.acceptPacket(notice);assertTrue(client.bombs().state().explosions().isEmpty());
            var planted=full(1).withEntities(List.of(new EntityData(EntityData.Kind.BOMB,1,1,1,"s",2,3000)),List.of())
                    .withBombFrame(new BombFrame(1000,List.of()));
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(planted)));
            assertEquals(1,client.bombs().state().bombs().size());
            var exploded=full(2).withBombFrame(frame());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(exploded)));
            client.acceptPacket(notice);client.acceptPacket(notice);
            assertEquals(1,client.bombs().noticeCount());assertEquals(1,client.bombs().state().explosions().size());
            assertTrue(client.bombs().state().bombs().isEmpty());assertTrue(client.world().getPlayers().get(1).isAlive(),"Events cannot damage players");
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(planted)));
            assertEquals(2,client.world().getTick());assertTrue(client.bombs().state().bombs().isEmpty());
            // A gap keeps the old visual scene until a full state establishes a baseline.
            var gap=GameStateUpdate.delta(5,4,3,3,32,GameState.MatchState.RUNNING,false,List.of(),List.of(),List.of());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(gap)));
            assertTrue(client.world().needsFullState());assertEquals(1,client.bombs().state().explosions().size());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encodeFullSync(full(6).withBombFrame(frame()))));
            assertFalse(client.world().needsFullState());assertEquals(1,client.bombs().state().explosions().size());
            client.acceptPacket(notice);assertEquals(1,client.bombs().noticeCount());
            client.acceptPacket(MatchEnvelopeCodec.wrap(UUID.randomUUID(),SnapshotPacketCodec.encode(full(100))));
            assertEquals(6,client.world().getTick());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encodeFullSync(full(7).withBombFrame(new BombFrame(1600,List.of())))));
            assertTrue(client.bombs().state().explosions().isEmpty());assertTrue(failures.isEmpty());
        }
    }
    @Test void restoredSceneUsesServerRemainingTimeAndMonotonicAnimationAndTv3Renderers() {
        var world=new ClientWorldState();world.apply(full(1));var scene=new BombScene();
        scene.synchronize(world,frame(),50000,1_000_000_000L);
        var e=scene.state().explosions().iterator().next();assertEquals(50500,e.visibleUntilMillis());
        var image=new BufferedImage(96,96,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try {scene.render(g,32,1_100_000_000L);} finally {g.dispose();}
        assertNotEquals(0,image.getRGB(48,48));assertEquals(1,scene.state().explosions().size());
        g=image.createGraphics();try {scene.render(g,32,1_500_000_000L);}finally {g.dispose();}
        assertTrue(scene.state().explosions().isEmpty());
        world.apply(full(2).withEntities(List.of(new EntityData(EntityData.Kind.BOMB,2,1,1,"s",1,1400)),List.of()));
        scene.synchronize(world,new BombFrame(1000,List.of()),50000,2_000_000_000L);
        var bombImage=new BufferedImage(96,96,BufferedImage.TYPE_INT_ARGB);g=bombImage.createGraphics();
        try {scene.render(g,32,2_600_000_000L);}finally {g.dispose();}
        assertNotEquals(0,bombImage.getRGB(48,48));assertEquals(1,scene.state().bombs().size(),"Countdown cannot remove an authoritative bomb");
    }
}

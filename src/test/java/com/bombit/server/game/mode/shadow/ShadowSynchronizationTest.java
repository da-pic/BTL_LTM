package com.bombit.server.game.mode.shadow;

import com.bombit.client.game.movement.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.replication.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Server projection → codec/framing TCP → client replica, gồm hide/reveal và epoch/baseline sai. */
class ShadowSynchronizationTest {
    private static MatchStartMetadata metadata(ShadowScanTest.Fixture f,int recipient){
        var context=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.FIRE,f.roster.identities());
        return new MatchStartMetadata(context,ShadowMatchSetup.newConfiguration(),1,f.roster.players(),new StreamContext(recipient,1,0,0));
    }
    private static WorldFrame frame(ShadowScanTest.Fixture f){return new WorldFrame(
            new StateDeltaBuilder().buildUpdate(f.state,f.state.getShadowFrame().tick(),true),f.clock.get()/1_000_000,10000,Set.of(),List.of(),f.state.getShadowFrame(),f.scans());}
    private static Packet wire(ShadowRecipientStream stream,ShadowSnapshotV6 update)throws IOException{
        return MatchEnvelopeV3Codec.update(stream.metadata().identity().matchId(),stream.metadata().stream(),SnapshotV6Codec.encode(update,10000,stream.metadata().identity()));
    }
    private static Set<Integer> ids(ShadowReplica replica){return replica.world().getPlayers().keySet();}
    private static final class Tcp implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0);
        final Socket reader=new Socket("127.0.0.1",listener.getLocalPort());
        final Socket writer=listener.accept();
        Tcp()throws IOException{reader.setSoTimeout(2000);}
        Packet transfer(Packet packet)throws IOException{writer.getOutputStream().write(PacketEncoder.encode(packet));writer.getOutputStream().flush();return PacketDecoder.decode(reader.getInputStream());}
        public void close()throws IOException{reader.close();writer.close();listener.close();}
    }

    @Test void independentRecipientStreamsRoundTripFullEnterExitRevealOverTcp()throws Exception{
        try(var f=new ShadowScanTest.Fixture();var tcp=new Tcp()){
            var meta=metadata(f,1);var a=new ShadowRecipientStream(meta);
            var b=new ShadowRecipientStream(meta.withStream(new StreamContext(2,1,0,0)));
            var replica=new ShadowReplica(meta);var other=new ShadowReplica(b.metadata());
            f.at(0);var first=a.build(frame(f));var firstB=b.build(frame(f));
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(tcp.transfer(wire(a,first))));a.commit(first);
            assertEquals(ClientWorldState.Result.APPLIED,other.accept(tcp.transfer(wire(b,firstB))));b.commit(firstB);
            assertEquals(Set.of(1,3),ids(replica));assertEquals(Set.of(2,4),ids(other));
            f.move(2,3,1);f.state.getPlayer(1).acknowledgeInput(7);f.state.getPlayer(2).acknowledgeInput(99);f.at(50);
            var enter=a.build(frame(f));assertFalse(enter.update().isFullState());assertEquals(first.update().getTick(),enter.update().getBaseTick());
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(tcp.transfer(wire(a,enter))));a.commit(enter);
            assertEquals(Set.of(1,2,3),ids(replica));assertEquals(7,replica.world().getPlayers().get(1).getLastProcessedInput());
            assertEquals(0,replica.world().getPlayers().get(2).getLastProcessedInput());
            f.move(2,15,1);f.at(100);var exit=a.build(frame(f));assertEquals(List.of(2),exit.update().getRemovedPlayerIds());
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(tcp.transfer(wire(a,exit))));a.commit(exit);
            assertEquals(Set.of(1,3),ids(replica));assertEquals(Set.of(1,3),replica.world().getMatchFrame().players().stream().map(MatchFrame.Stats::playerId).collect(java.util.stream.Collectors.toSet()));
            f.move(2,3,2);f.at(150);var reveal=a.build(frame(f));
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(tcp.transfer(wire(a,reveal))));a.commit(reveal);
            assertEquals(3*32+4,replica.world().getPlayers().get(2).getX());assertEquals(2*32+4,replica.world().getPlayers().get(2).getY());
            assertEquals(4,replica.publicPlayers().size());assertEquals(f.state.getShadowFrame().scoreA(),replica.objectives().scoreA());
        }
    }

    @Test void enqueueFailureSkippedDeltaAndResyncDoNotCommitOrMixEpochs()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var stream=new ShadowRecipientStream(metadata(f,1));var replica=new ShadowReplica(stream.metadata());
            f.at(0);var notEnqueued=stream.build(frame(f));f.at(50);var initial=stream.build(frame(f));
            assertTrue(initial.update().isFullState());assertThrows(IllegalStateException.class,()->stream.commit(notEnqueued));
            var oldPacket=wire(stream,initial);replica.accept(oldPacket);stream.commit(initial);
            f.at(100);var skipped=stream.build(frame(f));stream.commit(skipped);
            f.at(150);var delta=stream.build(frame(f));
            assertEquals(ClientWorldState.Result.NEED_FULL_STATE,replica.accept(wire(stream,delta)));
            assertEquals(initial.update().getTick(),replica.world().getTick());
            stream.reset(delta.update().getTick());replica.bind(stream.metadata());
            assertNull(stream.build(frame(f)));assertEquals(ClientWorldState.Result.STALE,replica.accept(oldPacket));
            f.at(200);var full=stream.build(frame(f));assertTrue(full.update().isFullState());
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(wire(stream,full)));stream.commit(full);
            assertFalse(replica.world().needsFullState());assertEquals(ClientWorldState.Result.STALE,replica.accept(wire(stream,full)));
            var wrong=MatchEnvelopeV3Codec.update(stream.metadata().identity().matchId(),new StreamContext(2,2,0,0),SnapshotV6Codec.encode(full,10000,stream.metadata().identity()));
            assertEquals(ClientWorldState.Result.STALE,replica.accept(wrong));
        }
    }

    @Test void scanEntityTransitionsPendingToBeaconToRemovalAndKeepsCooldownPrivate()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var stream=new ShadowRecipientStream(metadata(f,1));var replica=new ShadowReplica(stream.metadata());
            var placed=f.plant(2,1,BombType.SCAN);int id=placed.bombId();f.at(0);
            var pending=stream.build(frame(f));replica.accept(wire(stream,pending));stream.commit(pending);
            assertTrue(replica.world().getEntities().containsKey("SCAN_PENDING:"+id));assertEquals(0,replica.scanReadyAtMillis());
            f.at(1500);var active=stream.build(frame(f));assertTrue(active.update().getRemovedEntityKeys().contains("SCAN_PENDING:"+id));
            replica.accept(wire(stream,active));stream.commit(active);
            assertFalse(replica.world().getEntities().containsKey("SCAN_PENDING:"+id));assertTrue(replica.world().getEntities().containsKey("SCAN_BEACON:"+id));
            f.at(4500);var expired=stream.build(frame(f));replica.accept(wire(stream,expired));stream.commit(expired);
            assertTrue(replica.world().getEntities().isEmpty());
        }
    }

    @Test void finishedTeamResultSurvivesCodecAndReplicaWithoutIndividualWinner()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var stream=new ShadowRecipientStream(metadata(f,2));var replica=new ShadowReplica(stream.metadata());
            f.state.getPlayer(1).setLives(0);f.state.getPlayer(3).setLives(0);f.at(0);
            var update=stream.build(frame(f));assertFalse(update.inputEnabled());
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(wire(stream,update)));
            assertEquals(ShadowFrame.Reason.TEAM_ELIMINATED,replica.objectives().result().reason());
            assertEquals(TeamId.B,replica.objectives().result().winningTeam());assertNull(replica.world().getMatchFrame().result());
        }
    }

    private static byte[] raw(Packet packet){return Base64.getDecoder().decode((String)JsonV3.object(packet.getJsonPayload(),1048574).get("data"));}
    private static Packet rawPacket(Packet original,byte[] raw){return JsonV3.packet(original.getType(),JsonV3.body("snapshotVersion",6,"data",Base64.getEncoder().encodeToString(raw)));}
    @Test void decoderRejectsCorruptionAndEnemyDetailOutsideGrantedVision()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var meta=metadata(f,1);var stream=new ShadowRecipientStream(meta);f.at(0);var update=stream.build(frame(f));
            var packet=SnapshotV6Codec.encode(update,10000,meta.identity());var bytes=raw(packet);
            var bad=Arrays.copyOf(bytes,bytes.length+1);assertThrows(IOException.class,()->SnapshotV6Codec.decodeShadow(rawPacket(packet,bad),meta));
            var nan=bytes.clone();ByteBuffer.wrap(nan).putDouble(321,Double.NaN);assertThrows(IOException.class,()->SnapshotV6Codec.decodeShadow(rawPacket(packet,nan),meta));
            assertThrows(IOException.class,()->SnapshotV6Codec.decode(packet,meta));
            var details=new ArrayList<>(update.update().getPlayers());var enemy=frame(f).snapshot().getPlayers().stream().filter(p->p.getId()==2).findFirst().orElseThrow();details.add(enemy);
            var stats=new ArrayList<>(update.update().getMatchFrame().players());stats.add(new MatchFrame.Stats(2,1,1,3,false,0));
            var forged=com.bombit.server.game.movement.GameStateUpdate.fullStateModel(1,17,15,32,update.update().getMatchState(),update.update().mapTiles(),details)
                    .withBombFrame(update.update().getBombFrame()).withMatchFrame(new MatchFrame(180,stats,null));
            var leak=new ShadowSnapshotV6(forged,1,0,update.publicPlayers(),0,true,update.objectives(),update.visibleCells(),0);
            assertThrows(IOException.class,()->SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(leak,10000,meta.identity()),meta));
        }
    }

    @Test void movementControllerDiscardsInterpolationHistoryWhenEnemyHides()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var meta=metadata(f,1);var stream=new ShadowRecipientStream(meta);var errors=new ArrayList<Throwable>();
            try(var controller=new MovementClientController(new ClientNetwork(),1,Runnable::run,()->{},errors::add,meta.identity().matchId(),false)){
                controller.bindCurrentMetadata(meta);f.at(0);var full=stream.build(frame(f));
                controller.acceptSnapshotV6(SnapshotV6Codec.decodeShadow(SnapshotV6Codec.encode(full,10000,meta.identity()),meta));stream.commit(full);controller.resumeAfterRecovery();
                f.move(2,3,1);f.at(50);var enter=stream.build(frame(f));controller.acceptSnapshotV6(enter);stream.commit(enter);
                assertNotNull(controller.interpolation().sample(2,2));
                f.move(2,15,1);f.at(100);var exit=stream.build(frame(f));controller.acceptSnapshotV6(exit);stream.commit(exit);
                assertNull(controller.interpolation().sample(2,2));
                f.move(2,3,2);f.at(150);var reveal=stream.build(frame(f));controller.acceptSnapshotV6(reveal);stream.commit(reveal);
                assertNull(controller.interpolation().sample(2,2));assertEquals(2*32+4,controller.interpolation().sample(2,4).y());
                assertTrue(controller.isInputReady());assertTrue(errors.isEmpty(),errors.toString());
            }
        }
    }

    @Test void enemyBombEntersAndLeavesViewWhileOwnTeamBombStaysKnown()throws Exception{
        try(var f=new ShadowScanTest.Fixture()){
            var meta=metadata(f,1);var stream=new ShadowRecipientStream(meta);var replica=new ShadowReplica(meta);
            var enemy=f.plant(2,1,BombType.NORMAL);var ally=f.plant(3,1,BombType.NORMAL);
            f.game.processCommand(new com.bombit.server.game.core.PlayerDisconnectedCommand(3));
            f.at(0);var first=stream.build(new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,1,true),0,10000,Set.of(3),List.of(),f.state.getShadowFrame(),f.scans()));
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(wire(stream,first)));stream.commit(first);
            assertFalse(replica.world().getEntities().containsKey("BOMB:"+enemy.bombId()));
            assertTrue(replica.world().getEntities().containsKey("BOMB:"+ally.bombId()));
            assertFalse(replica.visibleCells().contains(new com.bombit.server.game.model.TilePosition(1,13)));
            f.move(1,13,1);f.at(50);var enter=stream.build(frame(f));
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(wire(stream,enter)));stream.commit(enter);
            assertTrue(replica.world().getEntities().containsKey("BOMB:"+enemy.bombId()));
            f.move(1,1,1);f.at(100);var exit=stream.build(frame(f));
            assertTrue(exit.update().getRemovedEntityKeys().contains("BOMB:"+enemy.bombId()));
            assertEquals(ClientWorldState.Result.APPLIED,replica.accept(wire(stream,exit)));stream.commit(exit);
            assertFalse(replica.world().getEntities().containsKey("BOMB:"+enemy.bombId()));
            assertTrue(replica.world().getEntities().containsKey("BOMB:"+ally.bombId()));
        }
    }
}

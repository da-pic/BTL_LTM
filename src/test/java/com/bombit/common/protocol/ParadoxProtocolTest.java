package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.client.game.movement.*;
import com.bombit.client.network.ClientNetwork;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.replication.*;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParadoxProtocolTest {
    @Test void rewindKeysAndHudRespectLockChargeCooldownAndTimeline()throws Exception{
        javax.swing.SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var errors=new ArrayList<Throwable>();
            try(var controller=new MovementClientController(new ClientNetwork(),1,Runnable::run,()->{},errors::add,f.identity.matchId(),false)){
                controller.bindCurrentMetadata(f.metadata(1,0));
                var hud=new com.bombit.client.ui.game.ParadoxHudPanel();hud.update(controller,1);
                assertTrue(hud.messageText().contains("khóa"));
                var root=new javax.swing.JPanel();var count=new java.util.concurrent.atomic.AtomicInteger();
                var ready=new java.util.concurrent.atomic.AtomicBoolean(false);
                try(var keys=new com.bombit.client.ui.game.BombInputBindings(root,null,ready::get,()->{},()->{},count::incrementAndGet,e->{throw e;})){
                    java.util.function.Consumer<String> key=stroke->{var name=root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(javax.swing.KeyStroke.getKeyStroke(stroke));root.getActionMap().get(name).actionPerformed(null);};
                    key.accept("pressed R");ready.set(true);key.accept("pressed R");assertEquals(0,count.get());
                    key.accept("released R");key.accept("pressed R");key.accept("pressed R");assertEquals(1,count.get());
                }
                assertNull(root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(javax.swing.KeyStroke.getKeyStroke("pressed R")));
                var first=new ParadoxRecipientStream(f.metadata(1,0)).build(f.frame(45,45,0));controller.acceptSnapshotV6(first);controller.resumeAfterRecovery();
                hud.update(controller,1);assertTrue(hud.chargeText().contains("1 / 1"));
                controller.bindCurrentMetadata(f.metadata(2,1));
                var source=new ParadoxRecipientStream(f.metadata(2,1)).build(f.frame(46,5,1));
                controller.acceptSnapshotV6(new ParadoxSnapshotV6(source.update(),5,2300,source.publicPlayers(),0,true,new ParadoxFrame(1,5,Set.of(2),3000)));
                controller.resumeAfterRecovery();hud.update(controller,1);
                assertTrue(hud.chargeText().contains("ĐÃ DÙNG"));assertTrue(hud.cooldownText().contains("3.0"));assertTrue(hud.timelineText().contains("Timeline 1"));
                controller.acceptRewindResult(new com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.RewindResult(1,1,0,
                        com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.Outcome.NOT_ENOUGH_HISTORY,1,1,0));
                assertTrue(controller.rewindFeedback().contains("lượt chưa bị tiêu"));assertTrue(errors.isEmpty(),errors.toString());
            }
        });
    }
    static final class Fixture {
        final String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        final MatchConfiguration config=new MatchConfiguration(UUID.randomUUID(),GameMode.PARADOX,"paradox-v1",MatchPreset.PARADOX_V1,
                MatchProfile.PUBLIC,"classic-generator-v1",new EffectiveRules.Paradox(EffectiveRules.Common.shadowRelayV1()));
        final MatchContext identity=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.ICE,
                List.of(new MatchContext.PlayerIdentity(a,1),new MatchContext.PlayerIdentity(b,2)));
        final GameState state=new GameState(new GameMap(17,15,32));
        Fixture(){
            state.addPlayer(new Player(1,36,36,24,24,80));state.addPlayer(new Player(2,484,420,24,24,80));state.startMatch();
            state.setMatchFrame(new MatchFrame(180,List.of(new MatchFrame.Stats(1,1,1),new MatchFrame.Stats(2,1,1)),null));
            state.setBombFrame(new BombFrame(10000,List.of()));
        }
        MatchStartMetadata metadata(long view,long timeline){return new MatchStartMetadata(identity,config,1,
                List.of(new MatchStartMetadata.Member(a,1,1,"A",null),new MatchStartMetadata.Member(b,2,2,"B",null)),new StreamContext(1,view,timeline,0));}
        WorldFrame frame(long server,long sim,long timeline){
            return new WorldFrame(new StateDeltaBuilder().buildUpdate(state,server,true),server*50,10000,Set.of(),List.of(),null,null,Map.of(),
                    new ParadoxFrame(timeline,sim,timeline==0?Set.of(1,2):Set.of(2),0),List.of());
        }
        ParadoxSnapshotV6 wire(ParadoxSnapshotV6 snapshot,MatchStartMetadata metadata)throws Exception{
            var outer=MatchEnvelopeV3Codec.update(identity.matchId(),metadata.stream(),SnapshotV6Codec.encode(snapshot,10000,identity));
            return (ParadoxSnapshotV6)SnapshotV6Codec.decodeRecipient(MatchEnvelopeV3Codec.unwrapUpdate(outer,identity.matchId(),metadata.stream()),metadata);
        }
    }
    @Test void metadataAndEnvelopesRoundTripAndRejectOldOrFutureEpoch(){
        var f=new Fixture();var next=f.metadata(2,1);
        assertEquals(next,MetadataV3Codec.decodeStarted(MetadataV3Codec.started(next)));
        var inner=ParadoxCodec.rewind(1);
        assertEquals(inner.getJsonPayload(),MatchEnvelopeV3Codec.unwrapCommand(MatchEnvelopeV3Codec.command(f.identity.matchId(),1,inner),f.identity.matchId(),1).getJsonPayload());
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapCommand(MatchEnvelopeV3Codec.command(f.identity.matchId(),0,inner),f.identity.matchId(),1));
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapCommand(MatchEnvelopeV3Codec.command(f.identity.matchId(),2,inner),f.identity.matchId(),1));
        var old=MatchEnvelopeV3Codec.update(f.identity.matchId(),f.metadata(1,0).stream(),inner);
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapUpdate(old,f.identity.matchId(),next.stream()));
        var forged=MatchEnvelopeV3Codec.update(f.identity.matchId(),new StreamContext(1,3,0,0),inner);
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapUpdate(forged,f.identity.matchId(),next.stream()));
    }
    @Test void rewindStartsWithFullThenDeltaUsesServerTicksAndCommitsOnlyEnqueuedCandidate()throws Exception{
        var f=new Fixture();var old=new ParadoxRecipientStream(f.metadata(1,0));
        var first=old.build(f.frame(50,50,0));old.commit(first);
        var dropped=old.build(f.frame(51,51,0));
        var nextMeta=f.metadata(2,1);var stream=new ParadoxRecipientStream(nextMeta);
        assertNull(old.build(f.frame(52,11,1)));assertNull(stream.build(f.frame(51,51,0)));
        var full=stream.build(f.frame(52,11,1));assertTrue(full.update().isFullState());
        var client=new ClientWorldState();assertEquals(ClientWorldState.Result.APPLIED,client.apply(f.wire(full,nextMeta).update()));stream.commit(full);
        var skipped=stream.build(f.frame(53,12,1));var delta=stream.build(f.frame(54,13,1));
        assertFalse(delta.update().isFullState());assertEquals(52,delta.update().getBaseTick());
        assertEquals(13,delta.simulationTick());assertThrows(IllegalStateException.class,()->stream.commit(skipped));
        assertEquals(ClientWorldState.Result.APPLIED,client.apply(f.wire(delta,nextMeta).update()));stream.commit(delta);
        stream.reset(54);assertEquals(1,stream.metadata().stream().timelineEpoch());
        assertEquals(3,stream.metadata().stream().viewEpoch());assertTrue(stream.build(f.frame(55,14,1)).update().isFullState());
        old.close();assertThrows(IllegalStateException.class,()->old.commit(dropped));
    }
    @Test void modeBlobCannotForgeTimelineAndSimulationTickMayNotExceedServerTick()throws Exception{
        var f=new Fixture();var meta=f.metadata(2,1);var frame=new ParadoxRecipientStream(meta).build(f.frame(51,10,1));
        var packet=SnapshotV6Codec.encode(frame,10000,f.identity);
        assertThrows(IOException.class,()->SnapshotV6Codec.decodeRecipient(packet,f.metadata(1,0)));
        var raw=Base64.getDecoder().decode((String)JsonV3.object(packet.getJsonPayload(),1048574).get("data"));
        java.nio.ByteBuffer.wrap(raw).putLong(17,52);
        var bad=JsonV3.packet(packet.getType(),JsonV3.body("snapshotVersion",6,"data",Base64.getEncoder().encodeToString(raw)));
        assertThrows(IOException.class,()->SnapshotV6Codec.decodeRecipient(bad,meta));
    }
    @Test void controllerClearsPredictionInterpolationAndEffectsAndWaitsForMatchingFull()throws Exception{
        var f=new Fixture();var failures=new ArrayList<Throwable>();
        try(var controller=new MovementClientController(new ClientNetwork(),1,Runnable::run,()->{},failures::add,f.identity.matchId(),false)){
            var meta=f.metadata(1,0);controller.bindCurrentMetadata(meta);var stream=new ParadoxRecipientStream(meta);
            f.state.setBombFrame(new BombFrame(10000,List.of(new BombFrame.Explosion(1,10000,10600,Set.of(new TilePosition(1,1))))));
            var old=f.wire(stream.build(f.frame(1,1,0)),meta);controller.acceptSnapshotV6(old);controller.resumeAfterRecovery();
            assertTrue(controller.isInputReady());assertEquals(1,controller.bombs().state().explosions().size());
            var pending=controller.prediction().predict(Direction.RIGHT,true);assertEquals(1,controller.prediction().pendingCount());
            assertNotNull(controller.interpolation().sample(2,1));
            controller.bindCurrentMetadata(f.metadata(2,1));
            assertFalse(controller.isInputReady());assertEquals(0,controller.prediction().pendingCount());
            assertNull(controller.interpolation().sample(2,1));assertTrue(controller.bombs().state().explosions().isEmpty());
            assertThrows(IllegalStateException.class,controller::resumeAfterRecovery);
            controller.acceptSnapshotV6(old);assertFalse(controller.isInputReady());
            var next=new ParadoxRecipientStream(f.metadata(2,1));var full=next.build(f.frame(51,10,1));next.commit(full);
            controller.acceptSnapshotV6(f.wire(next.build(f.frame(52,11,1)),f.metadata(2,1)));
            assertTrue(controller.world().needsFullState());assertFalse(controller.isInputReady());
            f.state.setBombFrame(new BombFrame(12650,List.of()));
            var valid=new ParadoxRecipientStream(f.metadata(2,1)).build(f.frame(53,12,1));
            controller.acceptSnapshotV6(f.wire(valid,f.metadata(2,1)));controller.resumeAfterRecovery();assertTrue(controller.isInputReady());
            assertTrue(controller.bombs().state().explosions().isEmpty());assertEquals(53,controller.world().getTick());
            var delayed=new ParadoxRecipientStream(meta).build(f.frame(100,100,0));controller.acceptSnapshotV6(f.wire(delayed,meta));
            assertEquals(53,controller.world().getTick());assertEquals(0,controller.prediction().pendingCount());
            assertTrue(controller.prediction().predict(Direction.DOWN,false).sequence()>pending.sequence());
            assertThrows(IllegalArgumentException.class,()->controller.bindCurrentMetadata(f.metadata(3,0)));
            assertTrue(failures.isEmpty(),failures.toString());
        }
    }
}

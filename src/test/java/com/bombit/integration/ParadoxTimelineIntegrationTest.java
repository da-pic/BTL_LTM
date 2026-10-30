package com.bombit.integration;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.mode.paradox.ParadoxGameplayExtension;
import com.bombit.server.game.mode.shadow.ShadowModeFactory;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.GameStateUpdate;
import com.bombit.server.game.movement.MovePacketCodec;
import com.bombit.server.game.runtime.*;
import com.bombit.server.network.OutboundMessage;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** TCP thật và hai UI executor tuần tự, không bật Paradox trong registry production. */
@Timeout(25)
class ParadoxTimelineIntegrationTest {
    private interface Check{boolean done()throws Exception;}
    private void await(Check check)throws Exception{
        long end=System.nanoTime()+6_000_000_000L;
        while(System.nanoTime()<end){if(check.done())return;Thread.sleep(10);}fail("Paradox network deadline");
    }
    private ModeRuntimeFactory factory(){return new ModeRuntimeFactory(){
        public GameMode gameMode(){return GameMode.PARADOX;}
        public GameMap createMap(String room,MatchConfiguration config){return new GameMap(17,15,32);}
        public GameplayExtension createGameplay(String room,MatchConfiguration config,GameState state,Map<String,Integer> players){
            return new ParadoxGameplayExtension(state,players,new BombRules(3000,1,1),
                    new MatchRules(180000,.75,5,8,144,8,3,99),42);
        }
    };}
    @Test void twoApplicationClientsResetInPlaceRejectDelayedWireAndContinueOnDelta()throws Exception{
        var games=new GameRuntimeManager(new ModeRegistry(EffectiveRules.Common.shadowRelayV1()));
        try(var server=new LobbyGameServer(0,games)){
            var accept=new Thread(()->{try{server.start();}catch(Exception e){throw new RuntimeException(e);}});accept.start();
            try(var a=new BombClientIntegrationTest.Client(server.port(),"A",true);var b=new BombClientIntegrationTest.Client(server.port(),"B",true)){
                await(()->a.connection.sessionId()!=null&&b.connection.sessionId()!=null);
                a.command(PacketType.CREATE_ROOM,Map.of("roomName","Paradox","maxPlayers",2,"gameMode","PARADOX"));
                await(()->a.connection.roomState()!=null);String room=a.connection.roomState().roomId();
                b.command(PacketType.JOIN_ROOM,Map.of("roomId",room));await(()->b.connection.roomState()!=null);
                b.command(PacketType.READY,Map.of("ready",true));await(()->server.transport().getRoomManager().find(room).canStartGame());
                a.command(PacketType.START_GAME,Map.of());
                await(()->a.call(()->a.connection.movement()!=null&&a.connection.movement().isInputReady())&&b.call(()->b.connection.movement()!=null&&b.connection.movement().isInputReady()));
                var controller=a.call(a.connection::movement);var other=b.call(b.connection::movement);var old=a.connection.metadata();
                var runtime=games.findGame(room).orElseThrow();await(()->runtime.lastCompletedTick()>=45);
                a.call(()->{controller.send(controller.prediction().predict(Direction.RIGHT,true));controller.plantBomb();return null;});
                await(()->a.connection.lastPlantResult()!=null&&a.call(()->!controller.world().getEntities().isEmpty()));
                var pre=runtime.latestWorldFrame();
                // Gói cũ có tick rất lớn: chỉ epoch mới chặn được, không thể dựa vào tick stale.
                var source=pre.snapshot();
                var future=GameStateUpdate.fullStateModel(9999,17,15,32,source.getMatchState(),source.mapTiles(),source.getPlayers())
                        .withEntities(source.getEntityChanges(),List.of()).withBombFrame(source.getBombFrame()).withMatchFrame(source.getMatchFrame());
                var publicRoster=old.players().stream().map(p->new ClassicSnapshotV6.PublicPlayer(p.playerId(),0,0)).toList();
                var stale=MatchEnvelopeV3Codec.update(old.identity().matchId(),old.stream(),SnapshotV6Codec.encode(
                        new ParadoxSnapshotV6(future,9999,pre.matchTimeMillis(),publicRoster,1,true,new ParadoxFrame(0,9999,Set.of(1,2),0)),pre.epochOrigin(),old.identity()));
                long sequence=a.call(()->{var input=controller.prediction().predict(Direction.DOWN,true);controller.rewind();return input.sequence();});
                await(()->a.connection.metadata().stream().timelineEpoch()==1&&b.connection.metadata().stream().timelineEpoch()==1
                        &&a.call(controller::isInputReady)&&b.call(other::isInputReady));
                assertSame(controller,a.call(a.connection::movement));assertSame(other,b.call(b.connection::movement));
                assertEquals(old.identity(),a.connection.metadata().identity());assertEquals(old.configuration(),a.connection.metadata().configuration());
                assertEquals(0,a.call(()->controller.prediction().pendingCount()));assertTrue(a.call(()->controller.world().getEntities().isEmpty()));
                assertTrue(a.call(()->controller.bombs().state().bombs().isEmpty()));
                long fullTick=a.call(controller::lastAppliedFullTick);long generation=a.call(controller::inputGeneration);
                var session=server.transport().getSessionManager().find(a.connection.sessionId()).orElseThrow();
                session.getOutboundSender().send(session,OutboundMessage.fromPacket(stale));
                session.getOutboundSender().send(session,OutboundMessage.fromPacket(MetadataV3Codec.started(old.withStream(new StreamContext(1,1000,0,0)))));
                var oldResult=JsonV3.packet(PacketType.GAME_OVER,GameplayV3Codec.resultBody(new MatchResult(5,null,MatchResult.Reason.ABORTED,List.of()),GameMode.PARADOX));
                session.getOutboundSender().send(session,OutboundMessage.fromPacket(MatchEnvelopeV3Codec.update(old.identity().matchId(),old.stream(),oldResult)));
                var oldNotice=GameplayV3Codec.notice(9999,new GameNotice(999,new com.bombit.server.game.event.BombExplodedEvent(
                        pre.epochOrigin()+pre.matchTimeMillis(),999,new com.bombit.server.game.model.TilePosition(1,1),Set.of(new com.bombit.server.game.model.TilePosition(1,1)))),old.identity(),pre.epochOrigin());
                session.getOutboundSender().send(session,OutboundMessage.fromPacket(MatchEnvelopeV3Codec.update(old.identity().matchId(),old.stream(),oldNotice)));
                var oldMove=MatchEnvelopeV3Codec.command(old.identity().matchId(),0,MovePacketCodec.encode(new MovePacketCodec.Input(999,Direction.RIGHT,true)));
                a.connection.send(oldMove);
                assertEquals("STALE_CONTEXT",SessionV3Codec.errorCode(a.take(PacketType.ERROR)));
                a.call(()->{assertEquals(1,controller.metadata().stream().timelineEpoch());assertEquals(generation,controller.inputGeneration());
                    assertTrue(controller.world().getTick()<9999);assertTrue(controller.isInputReady());
                    assertSame(controller,a.connection.movement());assertNull(a.connection.lastResult());assertTrue(controller.bombs().state().explosions().isEmpty());return null;});
                long fresh=a.call(()->{var input=controller.prediction().predict(Direction.DOWN,false);controller.send(input);return input.sequence();});
                assertTrue(fresh>sequence);
                await(()->a.call(()->controller.world().getPlayers().get(1).getLastProcessedInput()==fresh&&controller.world().getTick()>fullTick));
                assertEquals(fullTick,a.call(controller::lastAppliedFullTick));
                b.call(()->{other.rewind();return null;});
                var rejected=ParadoxCodec.result(MatchEnvelopeV3Codec.unwrapUpdate(b.take(PacketType.REWIND_RESULT),b.connection.context().matchId(),b.connection.metadata().stream()));
                assertEquals(ParadoxGameplayExtension.Outcome.COOLDOWN,rejected.outcome());assertTrue(b.call(other::isInputReady));
                assertNull(runtime.failure());assertTrue(server.failure().isEmpty(),server.failure().toString());
            }finally{server.close();accept.join(2000);assertFalse(accept.isAlive());}
        }
    }
}

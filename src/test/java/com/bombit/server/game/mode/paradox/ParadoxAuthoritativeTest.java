package com.bombit.server.game.mode.paradox;

import com.bombit.common.dto.BombType;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.runtime.MatchRules;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.Outcome.*;

class ParadoxAuthoritativeTest {
    static final class Fixture implements AutoCloseable {
        final AtomicLong clock=new AtomicLong();
        final GameState state=new GameState(new GameMap(17,15,32));
        final ParadoxGameplayExtension game;
        Fixture(){this(180000);}
        Fixture(long duration){
            for(int i=1;i<=3;i++)state.addPlayer(new Player(i,4+i*64,68,24,24,80));state.startMatch();
            game=new ParadoxGameplayExtension(state,Map.of("one",1,"two",2,"three",3),new BombRules(3000,1,3),
                    new MatchRules(duration,1,5,8,144,8,3,99),42,e->{},r->{},clock::get,()->10000+clock.get()/1_000_000L);
            game.onStart();
        }
        void begin(){clock.addAndGet(50_000_000L);game.beforeTick();for(var p:state.getPlayers())p.beginTick();}
        void end(){game.update(10000+clock.get()/1_000_000L);}
        void steps(int n){for(int i=0;i<n;i++){begin();end();}}
        void request(int player,long id,long epoch){game.processCommand(new RewindCommand(player,id,epoch));}
        ParadoxGameplayExtension.RewindResult last(){return game.status().results().getLast();}
        public void close(){game.onStop();}
    }
    @Test void exactFortyTickRestorePreservesInputSequenceAndDoesNotAdvanceRestoredWorld(){
        try(var f=new Fixture()){
            for(int i=1;i<=50;i++){f.begin();f.state.getPlayer(1).setPosition(68+i,68);f.end();}
            f.state.getPlayer(1).acknowledgeInput(900);f.begin();f.request(1,1,0);f.end();
            assertEquals(ACCEPTED,f.last().outcome());assertEquals(10,f.last().simulationTick());
            assertEquals(51,f.last().serverTick());assertEquals(1,f.game.status().timelineEpoch());
            assertEquals(78,f.state.getPlayer(1).getX());assertEquals(900,f.state.getPlayer(1).getLastProcessedInput());
            assertFalse(f.state.getPlayer(1).isMoving());assertFalse(f.game.status().availableCharges().contains(1));
            f.steps(1);assertEquals(11,f.game.status().simulationTick());assertEquals(52,f.game.status().serverTick());
        }
    }
    @Test void rejectedRetryDoesNotBecomeAcceptedLaterAndConflictingPayloadDoesNotRestore(){
        try(var f=new Fixture()){
            f.begin();f.request(1,1,0);f.end();assertEquals(NOT_ENOUGH_HISTORY,f.last().outcome());
            f.steps(45);f.begin();f.request(1,1,0);f.end();assertEquals(NOT_ENOUGH_HISTORY,f.last().outcome());
            assertEquals(0,f.game.status().timelineEpoch());assertTrue(f.game.status().availableCharges().contains(1));
            f.begin();f.request(1,2,0);f.end();var accepted=f.last();assertEquals(ACCEPTED,accepted.outcome());
            f.begin();f.request(1,2,0);f.end();assertEquals(accepted,f.last());assertEquals(1,f.game.status().timelineEpoch());
            f.begin();f.request(1,2,1);f.end();assertEquals(REQUEST_CONFLICT,f.last().outcome());
            f.begin();f.request(1,3,1);f.end();assertEquals(ALREADY_USED,f.last().outcome());
        }
    }
    @Test void sameTickBusyDoesNotConsumeSecondChargeAndCooldownUsesRealRuntimeClock(){
        try(var f=new Fixture()){
            f.steps(50);f.begin();f.request(1,1,0);f.request(2,1,0);f.end();
            assertEquals(REWIND_BUSY,f.last().outcome());assertTrue(f.game.status().availableCharges().contains(2));
            f.begin();f.request(2,2,0);f.end();assertEquals(STALE_TIMELINE,f.last().outcome());
            f.begin();f.request(2,3,1);f.end();assertEquals(COOLDOWN,f.last().outcome());
            f.steps(57);f.begin();f.request(2,4,1);f.end();assertEquals(ACCEPTED,f.last().outcome());
            assertEquals(2,f.game.status().timelineEpoch());assertEquals(29,f.game.status().simulationTick());
        }
    }
    @Test void frozenDeadRemovedAndFinishedPlayersCannotTriggerRewind(){
        try(var f=new Fixture()){
            f.steps(45);f.begin();f.game.processCommand(new PlayerDisconnectedCommand(1));f.request(1,1,0);f.end();
            assertEquals(NOT_ELIGIBLE,f.last().outcome());
            f.state.getPlayer(2).setLives(0);f.begin();f.request(2,1,0);f.end();assertEquals(NOT_ELIGIBLE,f.last().outcome());
            f.begin();f.game.processCommand(new PlayerDisconnectedCommand(3,true));f.state.removePlayer(3);f.request(3,1,0);f.end();
            // Chỉ player 1 còn sống, pipeline đã chốt LAST_ALIVE.
            assertEquals(GameState.MatchState.FINISHED,f.state.getMatchState());
            f.begin();f.request(1,2,0);f.end();assertEquals(NOT_RUNNING,f.last().outcome());assertEquals(0,f.game.status().historySize());
        }
    }
    @Test void acceptedRewindKeepsDisconnectRemovedIdentityAndPlantRequestCache(){
        try(var f=new Fixture()){
            f.steps(45);f.begin();
            f.game.processCommand(new PlantBombCommand(1,20,BombType.NORMAL));
            assertTrue(f.state.plantResults().getLast().accepted());
            f.game.processCommand(new PlayerDisconnectedCommand(2));
            f.game.processCommand(new PlayerDisconnectedCommand(3,true));f.state.removePlayer(3);
            f.request(1,1,0);f.end();assertEquals(ACCEPTED,f.last().outcome());
            assertNull(f.state.getPlayer(3));assertTrue(f.state.getEntities().isEmpty());
            f.begin();f.game.processCommand(new PlantBombCommand(1,20,BombType.NORMAL));
            assertTrue(f.state.plantResults().getLast().accepted());assertTrue(f.state.getEntities().isEmpty());
            f.game.processCommand(new PlantBombCommand(2,1,BombType.NORMAL));
            assertEquals("PLAYER_DISCONNECTED",f.state.plantResults().getLast().errorCode());f.end();
        }
    }
    @Test void expiredDeadlineCannotBeRewoundAndEvictedRequestsRemainRejected(){
        try(var f=new Fixture(3000)){
            f.steps(45);f.begin();
            for(int i=1;i<=70;i++)f.request(1,i,99);
            f.request(1,1,0);f.end();assertEquals(STALE_REQUEST,f.last().outcome());
            f.clock.set(3_000_000_000L);f.game.beforeTick();f.request(1,71,0);f.end();
            assertEquals(NOT_RUNNING,f.last().outcome());assertEquals(GameState.MatchState.FINISHED,f.state.getMatchState());
        }
    }
}

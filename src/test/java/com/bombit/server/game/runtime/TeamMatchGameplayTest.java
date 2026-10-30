package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.paradox.*;
import com.bombit.server.game.model.TilePosition;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamMatchGameplayTest {
    @Test void simultaneousRootsApplyOneDamageAndOneRespawnPerPlayer(){
        try(var f=new Fixture(3)){
            f.move(1,2,2);f.move(3,4,2);f.move(2,3,2);
            f.game.processCommand(new PlantBombCommand(1));f.game.processCommand(new PlantBombCommand(3));f.update(1000);
            assertEquals(2,f.state.getBombFrame().explosions().size());
            assertEquals(2,f.state.getPlayer(2).getLives());assertEquals(1,f.state.getPlayer(2).getRespawns());assertNull(f.result());
        }
    }
    @Test void simultaneousRootsConsumeShieldWithoutSecondHitInSameBatch(){
        try(var f=new Fixture(3)){
            f.move(1,2,2);f.move(3,4,2);f.move(2,3,2);f.state.getPlayer(2).setShield(true);
            f.game.processCommand(new PlantBombCommand(1));f.game.processCommand(new PlantBombCommand(3));f.update(1000);
            assertFalse(f.state.getPlayer(2).hasShield());assertEquals(3,f.state.getPlayer(2).getLives());
            assertEquals(0,f.state.getPlayer(2).getRespawns());assertNull(f.result());
        }
    }
    static final class Fixture implements AutoCloseable{
        final AtomicLong clock=new AtomicLong();final GameState state=new GameState(new GameMap(17,15,32));
        final MatchGameplayExtension game;
        Fixture(int lives){
            for(int id=1;id<=4;id++){var cell=TeamMatchPolicy.spawnForSlot(id);state.addPlayer(new Player(id,cell.x()*32+4,cell.y()*32+4,24,24,80));}
            state.startMatch();game=new MatchGameplayExtension(state,Map.of("one",1,"two",2,"three",3,"four",4),new BombRules(1000,1,1),
                    new MatchRules(5000,0,5,8,144,8,lives,99),new ParadoxItemSpawnPolicy(0,42),e->{},r->{},clock::get,()->10000+clock.get()/1_000_000L);
            game.useTeams(List.of(1,2,3,4));game.onStart();
        }
        void update(long millis){clock.set(millis*1_000_000);game.update(10000+millis);}
        TeamMatchResult result(){return state.getMatchFrame().teamResult();}
        void move(int id,int x,int y){state.getPlayer(id).setPosition(x*32+4,y*32+4);}
        public void close(){game.onStop();}
    }
    @Test void slotTeamsAndSpawnsAreStableAndOppositeSides(){
        var policy=new TeamMatchPolicy(List.of(10,30,20,40));assertEquals(TeamId.A,policy.teams().get(10));assertEquals(TeamId.A,policy.teams().get(20));
        assertEquals(TeamId.B,policy.teams().get(30));assertEquals(TeamId.B,policy.teams().get(40));
        assertThrows(UnsupportedOperationException.class,()->policy.teams().clear());assertThrows(IllegalArgumentException.class,()->new TeamMatchPolicy(List.of(1,2,3)));
        assertEquals(1,TeamMatchPolicy.spawnForSlot(1).x());assertEquals(1,TeamMatchPolicy.spawnForSlot(3).x());
        assertEquals(15,TeamMatchPolicy.spawnForSlot(2).x());assertEquals(15,TeamMatchPolicy.spawnForSlot(4).x());
        assertEquals(4,java.util.stream.IntStream.rangeClosed(1,4).mapToObj(TeamMatchPolicy::spawnForSlot).distinct().count());
    }
    @Test void oneDeathDoesNotEndGameButEliminatingWholeTeamDoes(){
        try(var f=new Fixture(1)){
            f.state.getPlayer(2).setLives(0);f.update(50);assertNull(f.result());assertEquals(GameState.MatchState.RUNNING,f.state.getMatchState());
            f.state.getPlayer(4).setLives(0);f.update(100);assertEquals(TeamId.A,f.result().winningTeam());
            assertEquals(TeamMatchResult.Reason.TEAM_ELIMINATED,f.result().reason());assertNull(f.state.getMatchFrame().result());
            var finalResult=f.result();f.update(150);assertEquals(finalResult,f.result());
            assertThrows(IllegalStateException.class,f.game::captureCheckpoint);
        }
    }
    @Test void friendlyFireCanEliminateOwnTeamAndOpponentsWin(){
        try(var f=new Fixture(1)){
            f.move(1,2,2);f.move(3,2,3);f.game.processCommand(new PlantBombCommand(1));f.update(1000);
            assertFalse(f.state.getPlayer(1).isAlive());assertFalse(f.state.getPlayer(3).isAlive());
            assertEquals(TeamId.B,f.result().winningTeam());assertEquals(TeamMatchResult.Reason.TEAM_ELIMINATED,f.result().reason());
        }
    }
    @Test void sameBatchEliminatingBothTeamsIsDraw(){
        try(var f=new Fixture(1)){
            for(int id=1;id<=4;id++)f.move(id,2,2);
            f.game.processCommand(new PlantBombCommand(1));f.update(1000);
            assertEquals(TeamMatchResult.Reason.BOTH_TEAMS_ELIMINATED,f.result().reason());assertNull(f.result().winningTeam());
        }
    }
    @Test void respawnAndShieldKeepTeamAliveUntilLastLife(){
        try(var f=new Fixture(3)){
            f.move(1,2,2);f.move(3,2,3);f.state.getPlayer(3).setShield(true);f.game.processCommand(new PlantBombCommand(1));f.update(1000);
            assertEquals(2,f.state.getPlayer(1).getLives());assertEquals(1,f.state.getPlayer(1).getRespawns());
            assertEquals(3,f.state.getPlayer(3).getLives());assertFalse(f.state.getPlayer(3).hasShield());assertNull(f.result());
        }
    }
    @Test void timeoutIsDrawEvenWhenTeamHasMoreLivesAndEliminationHasPriority(){
        try(var f=new Fixture(3)){
            f.state.getPlayer(2).setLives(1);f.state.getPlayer(4).setLives(0);f.update(5000);
            assertEquals(TeamMatchResult.Reason.TIMEOUT,f.result().reason());assertNull(f.result().winningTeam());
        }
        try(var f=new Fixture(3)){
            f.state.getPlayer(2).setLives(0);f.state.getPlayer(4).setLives(0);f.update(5000);
            assertEquals(TeamMatchResult.Reason.TEAM_ELIMINATED,f.result().reason());assertEquals(TeamId.A,f.result().winningTeam());
        }
    }
    @Test void frozenSurvivorStillCountsButRemovedIdentityDoesNot(){
        try(var f=new Fixture(1)){
            f.state.getPlayer(2).setLives(0);f.game.processCommand(new PlayerDisconnectedCommand(4));f.update(50);assertNull(f.result());
            f.state.removePlayer(4);f.update(100);assertEquals(TeamId.A,f.result().winningTeam());
        }
    }
    @Test void paradoxUsesSameTeamDecisionAndCannotRewindFinalResult(){
        var state=new GameState(new GameMap(17,15,32));for(int i=1;i<=4;i++)state.addPlayer(new Player(i,i*32+4,36,24,24,80));state.startMatch();
        var game=new ParadoxGameplayExtension(state,Map.of("one",1,"two",2,"three",3,"four",4),new BombRules(3000,1,1),
                new MatchRules(180000,0,5,8,144,8,3,99),42).useTeams(List.of(1,2,3,4));
        game.onStart();try{
            state.getPlayer(2).setLives(0);state.getPlayer(4).setLives(0);game.beforeTick();game.update(System.currentTimeMillis());
            assertEquals(TeamId.A,state.getMatchFrame().teamResult().winningTeam());assertNull(state.getMatchFrame().result());
            game.beforeTick();game.processCommand(new RewindCommand(1,1,0));game.update(System.currentTimeMillis());
            assertEquals(ParadoxGameplayExtension.Outcome.NOT_RUNNING,game.status().results().getLast().outcome());assertEquals(0,game.status().historySize());
        }finally{game.onStop();}
    }
}

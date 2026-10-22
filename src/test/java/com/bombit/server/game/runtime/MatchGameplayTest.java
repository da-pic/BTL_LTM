package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.StateDeltaBuilder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchGameplayTest {
    private static final MatchRules RULES=new MatchRules(5000,1,3,4,200,25);
    private static class World implements AutoCloseable {
        final GameState state=new GameState(new GameMap(10,8,32));
        final AtomicLong nanos=new AtomicLong();
        final List<GameEvent> events=new ArrayList<>();final List<BombCommandResult> results=new ArrayList<>();
        final Map<String,Integer> ids=new LinkedHashMap<>();final MatchGameplayExtension extension;
        World(int[][] cells){
            for(int y=0;y<8;y++)for(int x=0;x<10;x++)if(x==0||y==0||x==9||y==7)state.getMap().setTile(x,y,TileType.HARD_WALL);
            for(int n=0;n<cells.length;n++){int id=n+1;ids.put("s"+id,id);state.addPlayer(new Player(id,cells[n][0]*32,cells[n][1]*32,24,24,150));}
            state.startMatch();extension=new MatchGameplayExtension(state,ids,new BombRules(1000,1,1),RULES,p->Optional.of(ItemType.BOMB_UP),events::add,results::add,nanos::get,()->10000L);extension.onStart();
        }
        void plant(int id){extension.processCommand(new PlantBombCommand(id));}
        void move(int id,int x,int y){state.getPlayer(id).setPosition(x*32,y*32);}
        void time(long millis){nanos.set(millis*1_000_000);extension.update(10000+millis);}
        MatchResult result(){return state.getMatchFrame().result();}
        public void close(){extension.onStop();assertTrue(state.getEntities().isEmpty());assertTrue(state.getGameNotices().isEmpty());}
    }
    @Test void blockSpawnsOnceAndSimultaneousPickupHasOneWinnerAndUpgradedQuotaIsUsedByBombs(){
        try(var w=new World(new int[][]{{1,1},{5,5},{7,5}})){
            w.state.getMap().setTile(2,1,TileType.BREAKABLE_BLOCK);w.plant(1);w.move(1,1,3);w.time(1000);
            assertEquals(TileType.EMPTY,w.state.getMap().tileAt(2,1));
            assertEquals(1,w.events.stream().filter(ItemSpawnedEvent.class::isInstance).count());
            assertEquals(1,w.state.getEntities().stream().filter(e->e.kind()==com.bombit.server.game.movement.EntityData.Kind.ITEM).count());
            w.move(2,2,1);w.move(3,2,1);w.time(1100);
            assertEquals(2,w.state.getPlayer(2).getBombLimit());assertEquals(1,w.state.getPlayer(3).getBombLimit());
            assertEquals(1,w.events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
            w.time(1200);assertEquals(1,w.events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
            w.plant(2);w.move(2,3,1);w.plant(2);w.move(2,4,1);w.plant(2);
            assertEquals(BombError.BOMB_LIMIT_REACHED,w.results.get(w.results.size()-1).error());
            var builder=new StateDeltaBuilder();var snapshot=builder.buildUpdate(w.state,4,true);
            assertEquals(2,snapshot.getMatchFrame().players().stream().filter(p->p.playerId()==2).findFirst().orElseThrow().bombLimit());
        }
    }
    @Test void sameTickDeathsDrawAndNeverProduceAnIntermediateWinner(){
        try(var w=new World(new int[][]{{1,1},{2,1}})){
            w.plant(1);w.time(1000);var result=w.result();assertEquals(MatchResult.Reason.ALL_DEAD,result.reason());assertNull(result.winnerPlayerId());
            assertEquals(List.of(new MatchResult.Ranking(1,1),new MatchResult.Ranking(2,1)),result.rankings());
            assertEquals(2,w.events.stream().filter(PlayerDiedEvent.class::isInstance).count());
            assertEquals(GameState.MatchState.FINISHED,w.state.getMatchState());w.time(2000);assertSame(result,w.result());
            w.plant(2);assertEquals(BombError.MATCH_NOT_RUNNING,w.results.get(w.results.size()-1).error());
        }
    }
    @Test void lastAliveWinsAndEarlierDeathsRankLower(){
        try(var w=new World(new int[][]{{1,1},{4,1},{7,5}})){
            w.plant(1);w.time(1000);assertNull(w.result());w.plant(2);w.time(2000);
            assertEquals(MatchResult.Reason.LAST_ALIVE,w.result().reason());assertEquals(3,w.result().winnerPlayerId());
            assertEquals(List.of(new MatchResult.Ranking(3,1),new MatchResult.Ranking(2,2),new MatchResult.Ranking(1,3)),w.result().rankings());
        }
    }
    @Test void timeoutUsesMonotonicDeadlineAndSurvivorsTie(){
        try(var w=new World(new int[][]{{1,1},{7,5}})){
            w.time(4999);assertNull(w.result());assertEquals(1,w.state.getMatchFrame().remainingSeconds());
            w.time(5000);assertEquals(MatchResult.Reason.TIMEOUT,w.result().reason());assertNull(w.result().winnerPlayerId());
            assertTrue(w.result().rankings().stream().allMatch(p->p.rank()==1));
        }
    }
    @Test void itemAdapterCapsAllStatsRejectsForeignThreadsAndDoesNotReviveDeadPlayers()throws Exception{
        var state=new GameState(new GameMap(5,5,32));var player=new Player(1,32,32,24,24,150);state.addPlayer(player);
        var adapter=new ItemWorldAdapter(state,Map.of("s",1),new MatchRules(1000,1,2,3,175,25));
        assertThrows(IllegalStateException.class,()->adapter.isPlayerAlive("s"));adapter.bindToGameThread();
        for(int n=0;n<5;n++)for(var type:ItemType.values())adapter.applyItemEffect("s",type);
        assertEquals(2,player.getBombLimit());assertEquals(3,player.getBombRange());assertEquals(175,player.getSpeed());
        var executor=Executors.newSingleThreadExecutor();
        try{assertThrows(ExecutionException.class,()->executor.submit(()->adapter.applyItemEffect("s",ItemType.SPEED_UP)).get());}
        finally{executor.shutdownNow();assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS));}
        player.setAlive(false);adapter.applyItemEffect("s",ItemType.SPEED_UP);assertFalse(player.isAlive());
        assertThrows(IllegalArgumentException.class,()->new RandomItemSpawnPolicy(Double.NaN,new Random()));
    }
}

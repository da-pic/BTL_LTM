package com.bombit.server.game.runtime;

import com.bombit.server.game.core.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedItemsTest {
    private static final MatchRules RULES=new MatchRules(5000,1,5,8,300,25,2,99);
    private static final class World implements AutoCloseable {
        final GameState state=new GameState(new GameMap(10,8,32));
        final AtomicLong nanos=new AtomicLong();final List<GameEvent> events=new ArrayList<>();
        final MatchGameplayExtension game;
        World(ItemSpawnPolicy drops){
            state.addPlayer(new Player(1,32,32,24,24,150));state.addPlayer(new Player(2,224,160,24,24,150));
            state.startMatch();game=new MatchGameplayExtension(state,Map.of("a",1,"b",2),new BombRules(100,1,1),
                RULES,drops,events::add,r->{},nanos::get,()->1000L);game.onStart();
        }
        Player p(int id){return state.getPlayer(id);}
        void plant(int id){game.processCommand(new PlantBombCommand(id));}
        void time(int ms){nanos.set(ms*1_000_000L);game.update(1000+ms);}
        public void close(){game.onStop();}
    }
    @Test void onlyDestroyedBlocksDropItemsAndEveryBasicTypeCanBePickedUp(){
        for(var type:ItemType.values())try(var w=new World(cell->Optional.of(type))){
            assertTrue(w.state.getEntities().isEmpty());assertEquals(2,w.p(1).getLives());
            w.state.getMap().setTile(2,1,TileType.BREAKABLE_BLOCK);w.plant(1);w.p(1).setPosition(32,96);w.time(100);
            var item=w.state.getEntities().stream().filter(e->e.kind()==com.bombit.server.game.movement.EntityData.Kind.ITEM).findFirst().orElseThrow();
            assertEquals(type,ItemType.fromWireId(item.variant()));assertEquals(2,item.tileX());assertEquals(1,item.tileY());
            w.p(1).setPosition(64,32);w.time(110);
            assertTrue(w.state.getEntities().isEmpty());
            switch(type){
                case BOMB_UP->assertEquals(2,w.p(1).getBombLimit());
                case FIRE_UP->assertEquals(2,w.p(1).getBombRange());
                case SPEED_UP->assertEquals(175,w.p(1).getSpeed());
                case LIFE_UP->assertEquals(3,w.p(1).getLives());
                case SHIELD->assertTrue(w.p(1).hasShield());
            }
            assertEquals(1,w.events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
        }
    }
    @Test void shieldCannotStackAndOverlappingChainHitsConsumeOnlyOneDamageOpportunity(){
        try(var w=new World(cell->Optional.empty())){
            var adapter=new ItemWorldAdapter(w.state,Map.of("a",1),RULES);adapter.bindToGameThread();
            adapter.applyItemEffect("a",ItemType.SHIELD);adapter.applyItemEffect("a",ItemType.SHIELD);
            w.plant(1);w.p(2).setPosition(64,32);w.plant(2);w.p(1).setPosition(64,32);w.time(100);
            assertEquals(2,w.p(1).getLives());assertFalse(w.p(1).hasShield());assertEquals(0,w.p(1).getRespawns());
            assertEquals(1,w.p(2).getLives());assertEquals(224,w.p(2).getX());assertEquals(160,w.p(2).getY());
            assertEquals(1,w.p(2).getRespawns());assertTrue(w.p(2).isAlive());
            assertEquals(0,w.events.stream().filter(PlayerDiedEvent.class::isInstance).count());
            w.plant(1);w.time(200);
            assertEquals(1,w.p(1).getLives());assertEquals(32,w.p(1).getX());assertEquals(32,w.p(1).getY());
            assertFalse(w.p(1).isMoving());assertEquals(1,w.p(1).getRespawns());assertNull(w.state.getMatchFrame().result());
            w.plant(1);w.time(300);
            assertEquals(0,w.p(1).getLives());assertFalse(w.p(1).isAlive());
            assertEquals(2,w.state.getMatchFrame().result().winnerPlayerId());
            assertEquals(1,w.events.stream().filter(PlayerDiedEvent.class::isInstance).count());
        }
    }
    @Test void respawnKeepsUpgradesAndOneLifeIsLostAcrossSeveralOverlappingBlasts(){
        try(var w=new World(cell->Optional.empty())){
            w.p(1).setBombLimit(2);w.p(1).setBombRange(2);w.p(1).setSpeed(175);
            w.plant(1);w.p(1).setPosition(64,32);w.plant(1);w.time(100);
            assertEquals(1,w.p(1).getLives());assertEquals(1,w.p(1).getRespawns());
            assertEquals(2,w.p(1).getBombLimit());assertEquals(2,w.p(1).getBombRange());assertEquals(175,w.p(1).getSpeed());
            assertEquals(32,w.p(1).getX());assertNull(w.state.getMatchFrame().result());
        }
    }
    @Test void simultaneousHeartPickupHasExactlyOneWinnerAndCannotReviveEliminatedPlayer(){
        try(var w=new World(cell->Optional.of(ItemType.LIFE_UP))){
            w.state.getMap().setTile(2,1,TileType.BREAKABLE_BLOCK);w.plant(1);w.p(1).setPosition(32,96);w.time(100);
            w.p(1).setPosition(64,32);w.p(2).setPosition(64,32);w.time(110);w.time(120);
            assertEquals(3,w.p(1).getLives());assertEquals(2,w.p(2).getLives());
            assertEquals(1,w.events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
            w.p(2).setLives(0);var adapter=new ItemWorldAdapter(w.state,Map.of("b",2),RULES);adapter.bindToGameThread();
            adapter.applyItemEffect("b",ItemType.LIFE_UP);assertEquals(0,w.p(2).getLives());assertFalse(w.p(2).isAlive());
        }
    }
    @Test void defaultRangeMakesFiveCellCrossAndEnergyExtendsAllFourArms(){
        try(var w=new World(cell->Optional.empty())){
            w.p(1).setPosition(96,96);w.plant(1);w.time(100);
            var blast=w.events.stream().filter(BombExplodedEvent.class::isInstance).map(BombExplodedEvent.class::cast).findFirst().orElseThrow();
            assertEquals(Set.of(new TilePosition(3,3),new TilePosition(2,3),new TilePosition(4,3),new TilePosition(3,2),new TilePosition(3,4)),blast.affectedCells());
            var adapter=new ItemWorldAdapter(w.state,Map.of("a",1),RULES);adapter.bindToGameThread();adapter.applyItemEffect("a",ItemType.FIRE_UP);
            w.p(1).setPosition(96,96);w.plant(1);w.p(1).setPosition(32,192);w.time(200);
            var last=w.events.stream().filter(BombExplodedEvent.class::isInstance).map(BombExplodedEvent.class::cast).toList().getLast();
            assertEquals(9,last.affectedCells().size());
            for(var cell:List.of(new TilePosition(1,3),new TilePosition(5,3),new TilePosition(3,1),new TilePosition(3,5)))assertTrue(last.affectedCells().contains(cell));
        }
    }
    @Test void seededHighDropRateCoversAllFiveTypesAndZeroDropPolicyNeverSpawns(){
        var policy=new RandomItemSpawnPolicy(.75,new Random(123));var counts=new EnumMap<ItemType,Integer>(ItemType.class);
        for(int n=0;n<10000;n++)policy.chooseItem(new TilePosition(1,1)).ifPresent(t->counts.merge(t,1,Integer::sum));
        assertEquals(EnumSet.allOf(ItemType.class),counts.keySet());int total=counts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(total>7000&&total<8000);
        assertTrue(new RandomItemSpawnPolicy(0,new Random(123)).chooseItem(new TilePosition(1,1)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new MatchRules(1000,.75,5,8,300,25,0,99));
        assertThrows(IllegalArgumentException.class,()->new ItemEffectRegistry(Map.of()));
    }
}

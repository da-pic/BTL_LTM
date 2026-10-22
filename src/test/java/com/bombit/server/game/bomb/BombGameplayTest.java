package com.bombit.server.game.bomb;

import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.CollisionSystem;
import com.bombit.server.game.runtime.BombWorldAdapter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BombGameplayTest {
    private static final class World implements AutoCloseable {
        final GameState state=new GameState(map());
        final AtomicLong nanos=new AtomicLong(),epoch=new AtomicLong(10000);
        final List<GameEvent> events=new ArrayList<>();
        final List<BombCommandResult> results=new ArrayList<>();
        final Map<String,Integer> ids=new LinkedHashMap<>();
        BombGameplayExtension extension;
        World(int[][] cells,BombRules rules) {
            for(int n=0;n<cells.length;n++) {int id=n+1;ids.put("session-"+id,id);state.addPlayer(new Player(id,cells[n][0]*32,cells[n][1]*32,24,24,150));}
            state.startMatch();extension=new BombGameplayExtension(state,ids,rules,events::add,results::add,nanos::get,epoch::get);extension.onStart();
        }
        void plant(int id) {extension.processCommand(new PlantBombCommand(id));}
        void time(long millis) {nanos.set(millis*1_000_000);extension.update(epoch.get());}
        BombCommandResult last() {return results.get(results.size()-1);}
        public void close() {extension.onStop();}
    }
    private static GameMap map() {
        var map=new GameMap(10,8,32);
        for(int y=0;y<8;y++) for(int x=0;x<10;x++) if(x==0||y==0||x==9||y==7)map.setTile(x,y,TileType.HARD_WALL);
        return map;
    }
    @Test void placementChangesCellOnlyWhenServerPlayerCenterCrossesGridEdge(){
        double[][] cases={{51.999,32,1,1},{52,32,2,1},{52.001,32,2,1},
            {32,51.999,1,1},{32,52,1,2},{32,52.001,1,2},{52,52,2,2}};
        for(var sample:cases)try(var w=new World(new int[][]{{1,1}},new BombRules(1000,1,1))){
            var player=w.state.getPlayer(1);player.setPosition(sample[0],sample[1]);w.plant(1);
            assertEquals(BombError.NONE,w.last().error());var bomb=w.state.getEntities().getFirst();
            assertEquals((int)sample[2],bomb.tileX());assertEquals((int)sample[3],bomb.tileY());
            assertEquals(player.tilePosition(w.state.getMap()),new TilePosition(bomb.tileX(),bomb.tileY()));
        }
    }
    @Test void validatesAliveCellQuotaAndUsesServerHitboxCenter() {
        try(var w=new World(new int[][]{{1,1},{1,1}},new BombRules(1000,2,1))) {
            w.state.getPlayer(1).setPosition(56,32); // center x=68 -> tile 2, not top-left tile 1.
            w.plant(1);assertEquals(BombError.NONE,w.last().error());
            var bomb=w.state.getEntities().get(0);assertEquals(2,bomb.tileX());assertEquals("session-1",bomb.ownerId());assertEquals(11000,bomb.expiresAtMillis());
            w.plant(1);assertEquals(BombError.CELL_OCCUPIED,w.last().error());
            w.state.getPlayer(1).setPosition(128,128);w.plant(1);assertEquals(BombError.BOMB_LIMIT_REACHED,w.last().error());
            w.state.getMap().setTile(1,1,TileType.BREAKABLE_BLOCK);w.plant(2);assertEquals(BombError.INVALID_CELL,w.last().error());
            w.state.getPlayer(2).setAlive(false);w.plant(2);assertEquals(BombError.PLAYER_NOT_ALIVE,w.last().error());
            w.plant(99);assertEquals(BombError.PLAYER_NOT_FOUND,w.last().error());assertEquals(1,w.state.getEntities().size());
        }
    }
    @Test void deadlineIsMonotonicNeverEarlyAndExplosionReturnsQuota() {
        try(var w=new World(new int[][]{{1,1}},new BombRules(1000,1,1))) {
            w.plant(1);w.state.getPlayer(1).setPosition(160,160);
            w.epoch.set(Long.MAX_VALUE-1000);w.time(999);assertEquals(1,w.state.getEntities().size());assertEquals(0,count(w,BombExplodedEvent.class));
            w.epoch.set(1);w.time(1000);assertTrue(w.state.getEntities().isEmpty());assertTrue(w.state.getPlayer(1).isAlive());
            w.time(5000);assertEquals(1,count(w,BombExplodedEvent.class));
            w.plant(1);assertEquals(BombError.NONE,w.last().error());assertEquals(2,w.last().bombId());
        }
    }
    @Test void chainTriggersNewerBombsExactlyOnceAndStopsAtHardWalls() {
        try(var w=new World(new int[][]{{1,3},{3,3},{5,3}},new BombRules(1000,2,1))) {
            w.plant(1);w.time(500);w.plant(2);w.time(600);w.plant(3);
            w.time(999);assertEquals(3,w.state.getEntities().size());
            w.time(1000);assertTrue(w.state.getEntities().isEmpty());
            assertEquals(Set.of(1,2,3),w.events.stream().filter(BombExplodedEvent.class::isInstance).map(BombExplodedEvent.class::cast).map(BombExplodedEvent::bombId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(3,count(w,BombExplodedEvent.class));assertEquals(3,count(w,PlayerDiedEvent.class));
            for(GameEvent event:w.events) if(event instanceof BombExplodedEvent e) {
                assertTrue(e.affectedCells().stream().allMatch(p->p.x()>0&&p.x()<9&&p.y()>0&&p.y()<7));
                assertThrows(UnsupportedOperationException.class,()->e.affectedCells().clear());
            }
            w.time(5000);assertEquals(3,count(w,BombExplodedEvent.class));assertEquals(3,count(w,PlayerDiedEvent.class));
        }
    }
    @Test void destroyedBlockRemainsBarrierForChainAndIndependentRootsInSameTick() {
        for(boolean sameDeadline:List.of(false,true)) {
            try(var w=new World(new int[][]{{1,2},{2,2},{4,2}},new BombRules(1000,4,1))) {
                w.state.getMap().setTile(3,2,TileType.BREAKABLE_BLOCK);w.plant(1);
                if(!sameDeadline) w.time(500);
                w.plant(2);w.time(1000);
                assertEquals(TileType.EMPTY,w.state.getMap().tileAt(3,2));assertEquals(1,count(w,BlockDestroyedEvent.class));
                assertTrue(w.state.getPlayer(3).isAlive(),"Block shields player behind it for the entire batch");
                assertEquals(2,count(w,BombExplodedEvent.class));
                assertTrue(w.events.stream().filter(BombExplodedEvent.class::isInstance).map(BombExplodedEvent.class::cast)
                        .noneMatch(e->e.affectedCells().contains(new TilePosition(4,2))));
            }
        }
    }
    @Test void damageUsesHitboxOverlapAndExcludesTouchingEdge() {
        try(var w=new World(new int[][]{{1,1},{3,1},{3,1}},new BombRules(1000,1,1))) {
            w.state.getPlayer(2).setPosition(90,32); // overlaps tile 2 even though center is in tile 3.
            w.state.getPlayer(3).setPosition(96,32); // only touches tile 2 boundary.
            w.plant(1);w.time(1000);
            assertFalse(w.state.getPlayer(2).isAlive());assertTrue(w.state.getPlayer(3).isAlive());
            w.plant(2);assertEquals(BombError.PLAYER_NOT_ALIVE,w.last().error());
        }
    }
    @Test void bombBlocksOtherPlayersOwnerCanExitButCannotReenter() {
        try(var w=new World(new int[][]{{1,1},{3,1}},new BombRules(1000,1,1))) {
            w.plant(1);var collision=new CollisionSystem();collision.setEntities(w.state.getEntities());
            var owner=w.state.getPlayer(1);var guest=w.state.getPlayer(2);
            collision.move(guest,w.state.getMap(),-100,0);assertEquals(64,guest.getX());
            collision.move(owner,w.state.getMap(),40,0);assertEquals(72,owner.getX());
            collision.move(owner,w.state.getMap(),-100,0);assertEquals(64,owner.getX());
            w.state.getPlayer(1).setPosition(160,160);w.state.getPlayer(2).setPosition(160,128);w.time(1000);
            collision.setEntities(w.state.getEntities());collision.move(guest,w.state.getMap(),-100,0);assertEquals(60,guest.getX());
        }
    }
    @Test void disconnectedFinishedAndForeignThreadCommandsCannotChangeWorld() throws Exception {
        try(var w=new World(new int[][]{{1,1}},new BombRules(1000,1,1))) {
            w.extension.processCommand(new PlayerDisconnectedCommand(1));w.plant(1);assertEquals(BombError.PLAYER_DISCONNECTED,w.last().error());
            w.extension.processCommand(new PlayerReconnectedCommand(1));w.plant(1);assertEquals(BombError.NONE,w.last().error());
            var foreign=Executors.newSingleThreadExecutor();
            try {assertThrows(ExecutionException.class,()->foreign.submit(()->w.plant(1)).get());}
            finally {foreign.shutdownNow();assertTrue(foreign.awaitTermination(1,TimeUnit.SECONDS));}
            w.state.finishMatch();w.plant(1);assertEquals(BombError.MATCH_NOT_RUNNING,w.last().error());
            w.time(1000);assertEquals(0,count(w,BombExplodedEvent.class));
        }
    }
    @Test void adapterGuardsReadsAndWritesAndShutdownClearsProjection() throws Exception {
        var state=new GameState(map());state.addPlayer(new Player(1,32,32,24,24,150));state.startMatch();
        var adapter=new BombWorldAdapter(state,Map.of("s",1),new BombRules(1000,2,1),System::nanoTime);
        assertThrows(IllegalStateException.class,()->adapter.playerExists("s"));adapter.bindToGameThread();
        var bomb=new Bomb(1,"s",new TilePosition(1,1),0,1000,2);adapter.addBomb(bomb);
        assertThrows(IllegalArgumentException.class,()->adapter.addBomb(bomb));
        var foreign=Executors.newSingleThreadExecutor();
        try {
            var read=assertThrows(ExecutionException.class,()->foreign.submit(()->adapter.bombAt(new TilePosition(1,1))).get());
            assertInstanceOf(IllegalStateException.class,read.getCause());
            assertThrows(ExecutionException.class,()->foreign.submit(()->adapter.destroyBlock(new TilePosition(1,1))).get());
        } finally {foreign.shutdownNow();assertTrue(foreign.awaitTermination(1,TimeUnit.SECONDS));}
        adapter.clear();assertEquals(BombState.CANCELLED,bomb.state());assertTrue(state.getEntities().isEmpty());assertEquals(0,adapter.activeBombCount("s"));
    }
    @Test void rulesRequireExplicitValidParametersAndResultCollectionsAreImmutable() {
        assertThrows(IllegalArgumentException.class,()->new BombRules(0,2,1));
        assertThrows(IllegalArgumentException.class,()->new BombRules(1000,0,1));
        assertThrows(IllegalArgumentException.class,()->new BombRules(Long.MAX_VALUE,1,1));
        var cells=new HashSet<TilePosition>();cells.add(new TilePosition(1,1));
        var event=new BombExplodedEvent(0,1,new TilePosition(1,1),cells);cells.clear();assertEquals(1,event.affectedCells().size());
    }
    private static long count(World w,Class<? extends GameEvent> type) {return w.events.stream().filter(type::isInstance).count();}
}

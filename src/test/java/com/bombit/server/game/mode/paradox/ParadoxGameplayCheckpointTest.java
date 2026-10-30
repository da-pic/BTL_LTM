package com.bombit.server.game.mode.paradox;

import com.bombit.common.dto.BombType;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Trace dùng clock giả nhưng chạy pipeline bom, damage, drop và pickup thật. */
class ParadoxGameplayCheckpointTest {
    static final class Fixture implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final GameState state = new GameState(new GameMap(7, 7, 32));
        final List<GameEvent> events = new ArrayList<>();
        final MatchGameplayExtension game;
        Fixture() { this(180000, 42); }
        Fixture(long duration, long seed) {
            state.addPlayer(new Player(1, 68, 68, 24, 24, 80));
            state.addPlayer(new Player(2, 68, 100, 24, 24, 80));
            state.addPlayer(new Player(3, 164, 164, 24, 24, 80));
            state.getMap().setTile(3, 2, TileType.BREAKABLE_BLOCK); state.startMatch();
            game = new MatchGameplayExtension(state, Map.of("one",1,"two",2,"three",3), new BombRules(1000,1,3),
                    new MatchRules(duration,1,5,8,144,8,3,99), new ParadoxItemSpawnPolicy(1,seed), events::add,
                    result -> {}, clock::get, () -> 10000 + clock.get()/1_000_000L);
            game.onStart();
        }
        void plant(int id, long request) { game.processCommand(new PlantBombCommand(id,request,BombType.NORMAL)); }
        void step(long millis) { clock.addAndGet(millis*1_000_000L); game.update(10000+clock.get()/1_000_000L); }
        void move(int id,int x,int y) { state.getPlayer(id).setPosition(x*32+4,y*32+4); }
        public void close() { game.onStop(); }
    }

    /** Chỉ chuẩn hóa ID cấp mới đơn điệu và deadline trận thực; mọi state mô phỏng đều so sánh. */
    private Object normalized(MatchGameplayExtension.Checkpoint c) {
        return List.of(c.core(), c.bombs(), c.items().stream().map(i -> List.of(i.type(),i.position())).toList(),
                c.deaths(), c.simulationTick(), c.randomState());
    }

    private List<Object> normalizedEvents(List<GameEvent> events) {
        return events.stream().map(e -> (Object)switch (e) {
            case BombExplodedEvent b -> List.of("explode",b.bombId(),b.position(),b.affectedCells());
            case BlockDestroyedEvent b -> List.of("block",b.position());
            case ItemSpawnedEvent i -> List.of("drop",i.itemType(),i.position());
            case ItemPickedUpEvent i -> List.of("pickup",i.playerId(),i.itemType());
            case PlayerDiedEvent p -> List.of("death",p.playerId(),p.killerPlayerId(),p.cause());
            default -> throw new IllegalArgumentException("Unexpected replay event: " + e);
        }).toList();
    }

    @Test void fullTraceReplayRestoresFuseDamageRespawnDropPickupAndRandom() {
        try (var f = new Fixture()) {
            f.plant(1,1); f.move(1,0,0); f.step(400);
            var saved = f.game.captureCheckpoint();
            f.events.clear();
            assertEquals(400_000_000L,saved.bombs().bombs().getFirst().ageNanos());
            f.step(599); assertEquals(3,f.state.getPlayer(2).getLives());
            f.step(1); assertEquals(2,f.state.getPlayer(2).getLives());
            assertEquals(1,f.state.getPlayer(2).getRespawns());
            var afterExplosion = f.game.captureCheckpoint();
            assertEquals(1,afterExplosion.items().size());
            int firstItemId=afterExplosion.items().getFirst().id();
            f.move(1,3,2);f.step(50);
            var first = f.game.captureCheckpoint();
            var expectedEvents = normalizedEvents(f.events);
            assertTrue(first.items().isEmpty());
            long eventId = f.state.getGameNotices().getLast().eventId();
            f.state.getPlayer(1).acknowledgeInput(900);
            f.game.restoreCheckpoint(saved);
            f.events.clear();
            assertTrue(f.state.getGameNotices().isEmpty()); assertTrue(f.state.plantResults().isEmpty());
            assertEquals(900,f.state.getPlayer(1).getLastProcessedInput());
            assertEquals(3,f.state.getPlayer(2).getLives()); assertEquals(0,f.state.getPlayer(2).getRespawns());
            assertEquals(TileType.BREAKABLE_BLOCK,f.state.getMap().tileAt(3,2));
            f.step(599);assertEquals(3,f.state.getPlayer(2).getLives());
            f.step(1);
            var secondExplosion=f.game.captureCheckpoint();
            assertEquals(normalized(afterExplosion),normalized(secondExplosion));
            assertTrue(secondExplosion.items().getFirst().id()>firstItemId);
            f.move(1,3,2); f.step(50);
            assertEquals(normalized(first),normalized(f.game.captureCheckpoint()));
            assertEquals(expectedEvents,normalizedEvents(f.events));
            assertTrue(f.state.getGameNotices().getFirst().eventId()>eventId);
            // Checkpoint cũ không thay đổi sau hai nhánh; index bomb/cell vẫn ngăn đặt chồng.
            assertEquals(1,saved.bombs().bombs().size()); assertTrue(saved.items().isEmpty());
        }
    }

    @Test void explosionVisualRestoresRemainingTimeWithoutReapplyingDamage() {
        try(var f=new Fixture()) {
            f.plant(1,1);f.move(1,0,0);f.step(1000);f.step(200);
            var saved=f.game.captureCheckpoint();assertEquals(200_000_000L,saved.bombs().explosions().getFirst().ageNanos());
            f.step(1000);assertTrue(f.state.getBombFrame().explosions().isEmpty());
            f.game.restoreCheckpoint(saved);
            assertEquals(1,f.state.getBombFrame().explosions().size());
            f.step(399);assertEquals(1,f.state.getBombFrame().explosions().size());
            f.step(1);assertTrue(f.state.getBombFrame().explosions().isEmpty());
            assertEquals(2,f.state.getPlayer(2).getLives());assertEquals(1,f.state.getPlayer(2).getRespawns());
        }
    }

    @Test void restoresShieldAndExistingItemsAndNeverDuplicatesPickup() {
        try(var f=new Fixture()) {
            f.state.getPlayer(2).setShield(true);f.plant(1,1);f.move(1,0,0);
            var shield=f.game.captureCheckpoint();f.step(1000);
            assertFalse(f.state.getPlayer(2).hasShield()); assertEquals(3,f.state.getPlayer(2).getLives());
            var dropped=f.game.captureCheckpoint();
            f.move(1,3,2);f.step(50);assertTrue(f.game.captureCheckpoint().items().isEmpty());
            f.game.restoreCheckpoint(dropped);assertEquals(1,f.game.captureCheckpoint().items().size());
            f.move(1,3,2);f.step(50);var stats=f.state.getPlayer(1).captureSimulation();
            f.step(50);assertEquals(stats,f.state.getPlayer(1).captureSimulation());
            f.game.restoreCheckpoint(shield);assertTrue(f.state.getPlayer(2).hasShield());
            f.step(1000);assertEquals(3,f.state.getPlayer(2).getLives());assertFalse(f.state.getPlayer(2).hasShield());
        }
    }

    @Test void deathRankingBookkeepingRestoresButMatchDeadlineDoesNot() {
        try(var f=new Fixture(5000,42)) {
            f.state.getPlayer(2).setLives(1);f.plant(1,1);f.move(1,0,0);
            var saved=f.game.captureCheckpoint();f.step(1000);
            assertFalse(f.state.getPlayer(2).isAlive());assertTrue(f.game.captureCheckpoint().deaths().containsKey(2));
            f.step(3000);f.game.restoreCheckpoint(saved);
            assertTrue(f.state.getPlayer(2).isAlive());assertTrue(f.game.captureCheckpoint().deaths().isEmpty());
            assertEquals(1,f.state.getMatchFrame().remainingSeconds());
            f.step(1000);assertEquals(GameState.MatchState.FINISHED,f.state.getMatchState());
            assertThrows(IllegalStateException.class,()->f.game.restoreCheckpoint(saved));
        }
    }

    @Test void currentDisconnectAndRemovedIdentitySurviveRestore() {
        try(var f=new Fixture()) {
            f.state.getPlayer(1).setMoving(true);var saved=f.game.captureCheckpoint();
            f.game.processCommand(new PlayerDisconnectedCommand(1));f.state.removePlayer(3);
            f.game.restoreCheckpoint(saved);
            assertNull(f.state.getPlayer(3));assertFalse(f.state.getPlayer(1).isMoving());
            f.plant(1,10);assertFalse(f.state.plantResults().getLast().accepted());
            assertEquals("PLAYER_DISCONNECTED",f.state.plantResults().getLast().errorCode());
            assertTrue(f.game.captureCheckpoint().deaths().containsKey(3));
        }
    }

    @Test void invalidAndForeignCheckpointsFailBeforeMutatingWorld() {
        try(var f=new Fixture();var other=new Fixture()) {
            f.plant(1,1);var saved=f.game.captureCheckpoint();
            assertThrows(IllegalArgumentException.class,()->other.game.restoreCheckpoint(saved));
            var cells=new ArrayList<>(saved.core().tiles());cells.set(2*7+2,TileType.HARD_WALL);
            var invalidCore=new com.bombit.server.game.checkpoint.CoreWorldCheckpoint(7,7,32,cells,saved.core().players());
            var invalid=new MatchGameplayExtension.Checkpoint(1,saved.ownerId(),invalidCore,saved.bombs(),saved.items(),saved.deaths(),saved.simulationTick(),saved.randomState());
            assertThrows(IllegalArgumentException.class,()->f.game.restoreCheckpoint(invalid));
            assertEquals(saved,f.game.captureCheckpoint());
            assertThrows(UnsupportedOperationException.class,()->saved.bombs().bombs().clear());
        }
    }

    @Test void chainedBombsRestoreCellIndexQuotaAndOneDamagePerBatch() {
        try(var f=new Fixture()) {
            f.plant(1,1); f.move(1,2,1); f.plant(1,2); f.move(1,0,0);
            f.step(250);var saved=f.game.captureCheckpoint();assertEquals(2,saved.bombs().bombs().size());
            f.step(750);var first=f.game.captureCheckpoint();
            assertTrue(first.bombs().bombs().isEmpty());assertEquals(2,first.bombs().explosions().size());
            assertEquals(2,f.state.getPlayer(2).getLives());
            f.game.restoreCheckpoint(saved);
            f.move(1,2,1);f.plant(1,3);assertEquals("CELL_OCCUPIED",f.state.plantResults().getLast().errorCode());
            f.move(1,0,0);f.step(750);assertEquals(normalized(first),normalized(f.game.captureCheckpoint()));
            f.move(1,2,1);f.plant(1,4);assertTrue(f.state.plantResults().getLast().accepted());
            assertTrue(f.state.plantResults().getLast().bombId()>saved.bombs().bombs().getLast().id());
        }
    }

    @Test void nonCheckpointPolicyIsRejectedAndStoppedOrWrongThreadAccessCannotMutate() throws Exception {
        var state=new GameState(new GameMap(7,7,32));
        state.addPlayer(new Player(1,4,4,24,24,80));state.addPlayer(new Player(2,100,100,24,24,80));state.startMatch();
        var game=new MatchGameplayExtension(state,Map.of("one",1,"two",2),new BombRules(1000,1,1),
                new MatchRules(180000,0,5,8,144,8),cell->Optional.empty(),e->{},r->{});
        game.onStart();assertThrows(IllegalStateException.class,game::captureCheckpoint);game.onStop();
        try(var f=new Fixture()) {
            var saved=f.game.captureCheckpoint();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread=new Thread(()->{try{f.game.restoreCheckpoint(saved);}catch(Throwable e){failure.set(e);}});
            thread.start();thread.join();assertInstanceOf(IllegalStateException.class,failure.get());
            assertEquals(saved,f.game.captureCheckpoint());
        }
        assertThrows(IllegalStateException.class,game::captureCheckpoint);
    }
}

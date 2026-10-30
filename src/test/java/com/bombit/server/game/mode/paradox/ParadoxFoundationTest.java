package com.bombit.server.game.mode.paradox;

import com.bombit.server.game.checkpoint.CoreWorldCheckpoint;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TileType;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParadoxFoundationTest {
    private ParadoxTimeline<Integer> history(int ticks) {
        var timeline = new ParadoxTimeline<Integer>(List.of(1, 2));
        timeline.capture(0);
        for (int i = 1; i <= ticks; i++) { timeline.advanceTick(); timeline.capture(i); }
        return timeline;
    }

    @Test void rewindRestoresFortyTicksButNeverServerTickOrCharge() {
        var timeline = history(50);
        var restored = new AtomicReference<Integer>();
        assertEquals(ParadoxTimeline.Outcome.ACCEPTED, timeline.rewind(1, true, true, 0, restored::set));
        assertEquals(10, restored.get()); assertEquals(50, timeline.serverTick());
        assertEquals(10, timeline.simulationTick()); assertEquals(1, timeline.timelineEpoch());
        assertFalse(timeline.hasCharge(1)); assertTrue(timeline.hasCharge(2));
        assertEquals(11, timeline.historySize());
        assertEquals(ParadoxTimeline.Outcome.REWIND_BUSY, timeline.rewind(2, true, true, 0, restored::set));
        timeline.advanceTick(); timeline.capture(1001);
        assertEquals(ParadoxTimeline.Outcome.ALREADY_USED, timeline.rewind(1, true, true, 4_000_000_000L, restored::set));
    }

    @Test void earlyDeadFrozenUnknownAndFinishedRequestsDoNotConsumeCharge() {
        var timeline = history(39);
        assertEquals(ParadoxTimeline.Outcome.NOT_ENOUGH_HISTORY, timeline.rewind(1, true, true, 0, c -> fail()));
        assertEquals(ParadoxTimeline.Outcome.NOT_ELIGIBLE, timeline.rewind(1, true, false, 0, c -> fail()));
        assertEquals(ParadoxTimeline.Outcome.UNKNOWN_PLAYER, timeline.rewind(3, true, true, 0, c -> fail()));
        assertEquals(ParadoxTimeline.Outcome.NOT_RUNNING, timeline.rewind(1, false, true, 0, c -> fail()));
        assertTrue(timeline.hasCharge(1)); timeline.finish();
        assertEquals(0, timeline.historySize());
        assertEquals(ParadoxTimeline.Outcome.NOT_RUNNING, timeline.rewind(1, true, true, 0, c -> fail()));
        assertThrows(IllegalStateException.class, timeline::advanceTick);
    }

    @Test void cooldownUsesRuntimeTimeAndFutureBranchIsDiscarded() {
        var timeline = history(100);
        timeline.rewind(1, true, true, 500, c -> assertEquals(60, c));
        timeline.advanceTick(); timeline.capture(1061);
        assertEquals(ParadoxTimeline.Outcome.COOLDOWN, timeline.rewind(2, true, true, 3_000_000_499L, c -> fail()));
        for (int i = 62; i <= 100; i++) { timeline.advanceTick(); timeline.capture(1000 + i); }
        assertEquals(ParadoxTimeline.Outcome.ACCEPTED, timeline.rewind(2, true, true, 3_000_000_500L, c -> assertEquals(60, c)));
        assertEquals(140, timeline.serverTick()); assertEquals(60, timeline.simulationTick());
        assertEquals(2, timeline.timelineEpoch());
    }

    @Test void boundedHistoryAndFailedRestoreDoNotCommitTimeline() throws Exception {
        var timeline = history(10000); assertEquals(61, timeline.historySize());
        assertThrows(IllegalStateException.class, () -> timeline.rewind(1, true, true, 0, c -> {throw new IllegalStateException("restore failed");}));
        assertTrue(timeline.hasCharge(1)); assertEquals(0, timeline.timelineEpoch()); assertEquals(10000, timeline.simulationTick());
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {try {timeline.capture(7);} catch (Throwable e) {error.set(e);}});
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test void randomCheckpointReproducesFutureTraceExactly() {
        var random = new ParadoxRandom(42);
        for (int i = 0; i < 23; i++) random.nextLong();
        var checkpoint = random.captureCheckpoint();
        var expected = new ArrayList<Integer>();
        for (int i = 0; i < 1000; i++) expected.add(random.nextInt(7));
        random.restoreCheckpoint(checkpoint);
        for (int value : expected) assertEquals(value, random.nextInt(7));
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
    }

    @Test void coreRestoreIsImmutablePreservesAckAndDoesNotResurrectRemovedIdentity() {
        var state = new GameState(new GameMap(5, 5, 32));
        var player = new Player(1, 4, 4, 24, 24, 80);
        state.addPlayer(player); state.addPlayer(new Player(2, 100, 100, 24, 24, 80)); state.startMatch();
        player.setLives(3); player.setShield(true); player.setBombRange(4);
        state.getMap().setTile(2, 2, TileType.BREAKABLE_BLOCK);
        var checkpoint = CoreWorldCheckpoint.capture(state);
        player.setShield(false); player.takeDamage(); player.setPosition(60, 60); player.setBombRange(1);
        player.acknowledgeInput(100); state.getMap().destroyBlock(2, 2); state.removePlayer(2);
        checkpoint.restore(state);
        assertEquals(4, player.getX()); assertEquals(3, player.getLives()); assertEquals(0, player.getRespawns());
        assertEquals(4, player.getBombRange()); assertTrue(player.hasShield());
        assertEquals(100, player.getLastProcessedInput()); assertFalse(player.canAdvance());
        assertNull(state.getPlayer(2)); assertEquals(TileType.BREAKABLE_BLOCK, state.getMap().tileAt(2, 2));
        assertThrows(UnsupportedOperationException.class, () -> checkpoint.tiles().clear());
        state.finishMatch(); assertThrows(IllegalStateException.class, () -> checkpoint.restore(state));
    }
}

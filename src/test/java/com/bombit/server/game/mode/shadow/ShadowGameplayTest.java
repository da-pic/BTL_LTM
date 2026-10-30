package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.BombCommandResult;
import com.bombit.server.game.core.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.TileType;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Clock giả trên cùng game thread: kiểm biên tick và gameplay thật, không sleep chờ fuse/timeout. */
class ShadowGameplayTest {
    private static final class Fixture implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final GameState state = new GameState(new GameMap(17, 15, 32));
        final List<BombCommandResult> placements = new ArrayList<>();
        final ShadowGameplayExtension game;
        Fixture() { this(cell -> Optional.empty()); }
        Fixture(ItemSpawnPolicy drops) { this(drops, true); }
        Fixture(ItemSpawnPolicy drops, boolean start) {
            var members = new ArrayList<MatchStartMetadata.Member>();
            for (int id = 1; id <= 4; id++) {
                members.add(new MatchStartMetadata.Member(new UUID(0, id).toString(), id, id, "P" + id, ShadowRoster.teamForSlot(id)));
                var spawn = ShadowArena.spawn(id);
                state.addPlayer(new Player(id, spawn.x() * 32 + 4, spawn.y() * 32 + 4, 24, 24, 80));
            }
            state.startMatch();
            game = new ShadowGameplayExtension(state, new ShadowRoster(members), EffectiveRules.ShadowRelay.v1(),
                    drops, event -> { }, placements::add, clock::get, () -> 10000 + clock.get() / 1_000_000);
            if (start) game.onStart();
        }
        void move(int id, int x, int y) { state.getPlayer(id).setPosition(x * 32 + 4, y * 32 + 4); }
        void step(int count) { for (int i = 0; i < count; i++) {clock.addAndGet(50_000_000);update();} }
        void update() { game.update(10000 + clock.get() / 1_000_000); }
        ShadowFrame frame() { return state.getShadowFrame(); }
        ShadowFrame.Station north() { return frame().stations().getFirst(); }
        public void close() { game.onStop(); }
    }

    @Test void twoTeammatesDoNotAccelerateCaptureAndContestedScoringKeepsFraction() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.move(3, 8, 3);f.step(39);
            assertNull(f.north().owner());assertEquals(39, f.north().captureProgress());
            var old = f.frame();f.step(1);
            assertEquals(TeamId.A, f.north().owner());assertEquals(0, f.north().scoreProgress());assertEquals(0, f.frame().scoreA());
            f.step(19);assertEquals(19, f.north().scoreProgress());
            f.move(2, 8, 3);f.step(1);
            assertTrue(f.north().contested());assertEquals(19, f.north().scoreProgress());assertEquals(0, f.frame().scoreA());
            f.move(2, 15, 1);f.step(1);
            assertEquals(1, f.frame().scoreA());assertEquals(0, f.north().scoreProgress());
            assertNull(old.stations().getFirst().owner());assertEquals(39, old.stations().getFirst().captureProgress());
            assertThrows(UnsupportedOperationException.class, () -> old.stations().clear());
        }
    }

    @Test void interruptedCaptureResetsAndEmptyOwnedStationStillScores() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.step(20);f.move(1, 1, 1);f.step(1);
            assertEquals(0, f.north().captureProgress());assertNull(f.north().challenger());
            f.move(1, 8, 3);f.step(40);f.move(1, 1, 1);f.step(20);
            assertEquals(1, f.frame().scoreA());
            f.move(2, 8, 3);f.step(39);
            assertEquals(TeamId.A, f.north().owner());assertEquals(39, f.north().captureProgress());assertEquals(1, f.frame().scoreA());
            f.step(1);assertEquals(TeamId.B, f.north().owner());assertEquals(0, f.north().scoreProgress());
            f.step(20);assertEquals(1, f.frame().scoreB());
        }
    }

    @Test void simultaneousTargetScoreDrawTakesPriorityOverTimeoutAndResultIsFinal() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.move(2, 8, 11);f.step(1239);
            assertEquals(59, f.frame().scoreA());assertEquals(59, f.frame().scoreB());
            f.clock.set(180_000_000_000L);f.update();
            var frame = f.frame();var result = frame.result();
            assertEquals(60, result.scoreA());assertEquals(60, result.scoreB());assertNull(result.winningTeam());
            assertEquals(ShadowFrame.Reason.TARGET_SCORE, result.reason());assertEquals(GameState.MatchState.FINISHED, f.state.getMatchState());
            assertNull(f.state.getMatchFrame().result()); // Không dựng kết quả cá nhân giả cho Shadow.
            f.step(20);assertSame(frame, f.frame());
        }
    }

    @Test void teamEliminationPrecedesCaptureAndTimeoutButOneDeadTeammateDoesNotEndMatch() {
        try (var f = new Fixture()) {
            f.state.getPlayer(1).setLives(0);f.step(1);
            assertNull(f.frame().result());assertEquals(GameState.MatchState.RUNNING, f.state.getMatchState());
            f.move(3, 8, 3);f.step(39);assertEquals(39, f.north().captureProgress());
            f.state.getPlayer(3).setLives(0);f.clock.set(180_000_000_000L);f.update();
            assertEquals(ShadowFrame.Reason.TEAM_ELIMINATED, f.frame().result().reason());
            assertEquals(TeamId.B, f.frame().result().winningTeam());assertNull(f.north().owner());
        }
        try (var f = new Fixture()) {
            for (var p : f.state.getPlayers()) p.setLives(0);
            f.step(1);assertEquals(ShadowFrame.Reason.BOTH_TEAMS_ELIMINATED, f.frame().result().reason());assertNull(f.frame().result().winningTeam());
        }
    }

    @Test void timeoutUsesScoresAndFrozenLivingPlayerStillKeepsTeamAlive() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.step(60);assertEquals(1, f.frame().scoreA());
            f.game.processCommand(new PlayerDisconnectedCommand(1));f.state.getPlayer(3).setLives(0);
            f.step(1);assertNull(f.frame().result());assertEquals(0, f.north().captureProgress());
            f.clock.set(180_000_000_000L);f.update();
            assertEquals(ShadowFrame.Reason.TIMEOUT, f.frame().result().reason());assertEquals(TeamId.A, f.frame().result().winningTeam());
        }
        try (var f = new Fixture()) {
            f.clock.set(180_000_000_000L);f.update();assertNull(f.frame().result().winningTeam());
        }
    }

    @Test void bombsDamageSelfAndTeammatesRespawnKeepsUpgradesAndOfflineIsNotInvulnerability() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.move(3, 8, 3);
            f.state.getPlayer(1).setSpeed(96);f.state.getPlayer(1).setBombLimit(2);f.state.getPlayer(1).setBombRange(2);
            f.game.processCommand(new PlantBombCommand(1, 1, BombType.NORMAL));
            f.game.processCommand(new PlayerDisconnectedCommand(1));
            f.clock.set(3_000_000_000L);f.update();
            for (int id : List.of(1, 3)) {assertEquals(2, f.state.getPlayer(id).getLives());assertEquals(1, f.state.getPlayer(id).getRespawns());}
            assertEquals(36, f.state.getPlayer(1).getX());assertEquals(36, f.state.getPlayer(1).getY());
            assertEquals(96, f.state.getPlayer(1).getSpeed());assertEquals(2, f.state.getPlayer(1).getBombLimit());
            assertEquals(2, f.state.getPlayer(1).getBombRange());assertEquals(0, f.north().captureProgress());assertNull(f.frame().result());
            f.game.processCommand(new PlantBombCommand(1, 2, BombType.NORMAL));assertFalse(f.state.plantResults().getLast().accepted());
            f.game.processCommand(new PlayerReconnectedCommand(1));f.game.processCommand(new PlantBombCommand(1, 3, BombType.NORMAL));
            assertTrue(f.state.plantResults().getLast().accepted());
            f.game.processCommand(new PlantBombCommand(2, 1, BombType.SCAN));
            assertTrue(f.state.plantResults().getLast().accepted());assertEquals(BombType.SCAN,f.state.plantResults().getLast().bombType());
        }
    }

    @Test void brokenBlockDropsItemAndFrozenPlayerCanPickUpWithoutCapturing() {
        try (var f = new Fixture(cell -> Optional.of(ItemType.LIFE_UP))) {
            f.state.getMap().setTile(9, 3, TileType.BREAKABLE_BLOCK);
            f.move(1, 8, 3);f.game.processCommand(new PlantBombCommand(1));
            f.clock.set(3_000_000_000L);f.update();
            assertEquals(TileType.EMPTY, f.state.getMap().tileAt(9, 3));
            assertTrue(f.state.getEntities().stream().anyMatch(e -> e.kind() == com.bombit.server.game.movement.EntityData.Kind.ITEM));
            f.move(3, 9, 3);f.game.processCommand(new PlayerDisconnectedCommand(3));f.step(1);
            assertEquals(4, f.state.getPlayer(3).getLives());assertEquals(0, f.north().captureProgress());
            assertTrue(f.state.getEntities().stream().noneMatch(e -> e.kind() == com.bombit.server.game.movement.EntityData.Kind.ITEM));
        }
    }

    @Test void frozenPlayersDoNotCaptureAndAdministrativeAbortHasNoWinningTeam() {
        try (var f = new Fixture()) {
            f.move(1, 8, 3);f.step(5);f.game.processCommand(new PlayerDisconnectedCommand(1));f.step(1);
            assertEquals(0, f.north().captureProgress());
            f.game.processCommand(new PlayerReconnectedCommand(1));f.step(1);assertEquals(1, f.north().captureProgress());
            f.game.abort();assertEquals(ShadowFrame.Reason.ABORTED, f.frame().result().reason());assertNull(f.frame().result().winningTeam());
        }
    }

    @Test void realGameLoopAppliesForfeitOnOwnerThreadAndStopsCleanly() throws Exception {
        try (var f = new Fixture(cell -> Optional.empty(), false)) {
            var loop = new GameLoop(f.state, new com.bombit.server.game.movement.MovementSystem(), f.game, 16, 50_000_000L);
            loop.start();
            try {
                assertTrue(loop.submitCommand(new PlayerDisconnectedCommand(1, true)));
                assertTrue(loop.submitCommand(new PlayerDisconnectedCommand(3, true)));
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (System.nanoTime() < deadline && (loop.createUpdate() == null
                        || loop.createUpdate().getMatchState() != GameState.MatchState.FINISHED)) Thread.sleep(5);
                assertNotNull(loop.createUpdate());assertEquals(GameState.MatchState.FINISHED, loop.createUpdate().getMatchState());
            } finally { loop.stop(); }
            assertFalse(loop.isThreadAlive());assertNull(loop.getFailure());
            assertEquals(ShadowFrame.Reason.TEAM_ELIMINATED, f.frame().result().reason());
            assertEquals(TeamId.B, f.frame().result().winningTeam());
        }
    }
}

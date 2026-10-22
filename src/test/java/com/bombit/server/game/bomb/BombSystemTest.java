package com.bombit.server.game.bomb;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;

import com.bombit.server.game.event.GameEvent;
import com.bombit.server.game.event.PlayerDiedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombSystemTest {
    @Test
    void plantsOnlyOneBombPerCell() {
        FakeBombWorld world = new FakeBombWorld();
        world.addPlayer("p1", new TilePosition(2, 2), 2, 2);
        world.addPlayer("p2", new TilePosition(2, 2), 2, 2);
        BombSystem system = new BombSystem(3_000, event -> { });

        BombPlacementResult first = system.plantBomb("p1", 100, world);
        BombPlacementResult second = system.plantBomb("p2", 101, world);

        assertTrue(first.succeeded());
        assertFalse(second.succeeded());
        assertEquals(BombError.CELL_OCCUPIED, second.error());
    }

    @Test
    void explosionDestroysBreakableBlockAndStopsBehindIt() {
        FakeBombWorld world = new FakeBombWorld();
        world.addPlayer("owner", new TilePosition(1, 1), 1, 4);
        world.tiles.put(new TilePosition(3, 1), TileType.BREAKABLE_BLOCK);
        BombSystem system = new BombSystem(3_000, event -> { });
        Bomb bomb = system.plantBomb("owner", 100, world).bomb();

        ExplosionResult result = system.explodeBomb(bomb.id(), 3_100, world);

        assertTrue(result.affectedCells().contains(new TilePosition(3, 1)));
        assertFalse(result.affectedCells().contains(new TilePosition(4, 1)));
        assertTrue(result.destroyedBlocks().contains(new TilePosition(3, 1)));
        assertEquals(TileType.EMPTY, world.tileType(new TilePosition(3, 1)));
    }

    @Test
    void chainReactionExplodesEachBombOnce() {
        FakeBombWorld world = new FakeBombWorld();
        world.addPlayer("p1", new TilePosition(1, 1), 2, 3);
        BombSystem system = new BombSystem(3_000, event -> { });
        Bomb first = system.plantBomb("p1", 100, world).bomb();
        world.playerPositions.put("p1", new TilePosition(3, 1));
        Bomb second = system.plantBomb("p1", 200, world).bomb();

        ExplosionResult result = system.explodeBomb(first.id(), 3_100, world);

        assertEquals(List.of(first.id(), second.id()), result.explodedBombIds());
        assertTrue(world.bombs.isEmpty());
    }

    @Test
    void killsPlayerAndPublishesOneDeathEvent() {
        FakeBombWorld world = new FakeBombWorld();
        world.addPlayer("owner", new TilePosition(1, 1), 1, 2);
        world.addPlayer("victim", new TilePosition(2, 1), 1, 2);
        List<GameEvent> events = new ArrayList<>();
        BombSystem system = new BombSystem(3_000, events::add);
        Bomb bomb = system.plantBomb("owner", 100, world).bomb();

        ExplosionResult result = system.explodeBomb(bomb.id(), 3_100, world);

        assertTrue(result.killedPlayerIds().contains("victim"));
        assertFalse(world.alivePlayers.contains("victim"));
        assertEquals(1, events.stream()
                .filter(PlayerDiedEvent.class::isInstance)
                .map(PlayerDiedEvent.class::cast)
                .filter(event -> event.playerId().equals("victim"))
                .count());
    }

    private static final class FakeBombWorld implements BombWorld {
        private final Map<String, TilePosition> playerPositions = new HashMap<>();
        private final Map<String, Integer> bombLimits = new HashMap<>();
        private final Map<String, Integer> bombRanges = new HashMap<>();
        private final Set<String> alivePlayers = new HashSet<>();
        private final Map<TilePosition, TileType> tiles = new HashMap<>();
        private final Map<Integer, Bomb> bombs = new HashMap<>();

        void addPlayer(String playerId, TilePosition position, int bombLimit, int bombRange) {
            playerPositions.put(playerId, position);
            bombLimits.put(playerId, bombLimit);
            bombRanges.put(playerId, bombRange);
            alivePlayers.add(playerId);
        }

        @Override
        public boolean playerExists(String playerId) {
            return playerPositions.containsKey(playerId);
        }

        @Override
        public boolean isPlayerAlive(String playerId) {
            return alivePlayers.contains(playerId);
        }

        @Override
        public TilePosition playerPosition(String playerId) {
            return playerPositions.get(playerId);
        }

        @Override
        public int playerBombLimit(String playerId) {
            return bombLimits.get(playerId);
        }

        @Override
        public int playerBombRange(String playerId) {
            return bombRanges.get(playerId);
        }

        @Override
        public int activeBombCount(String playerId) {
            return (int) bombs.values().stream()
                    .filter(bomb -> bomb.ownerPlayerId().equals(playerId))
                    .count();
        }

        @Override
        public TileType tileType(TilePosition position) {
            if (position.x() < 0 || position.y() < 0 || position.x() > 10 || position.y() > 10) {
                return TileType.OUT_OF_BOUNDS;
            }
            return tiles.getOrDefault(position, TileType.EMPTY);
        }

        @Override
        public Optional<Bomb> bombAt(TilePosition position) {
            return bombs.values().stream().filter(bomb -> bomb.position().equals(position)).findFirst();
        }

        @Override
        public Optional<Bomb> bombById(int bombId) {
            return Optional.ofNullable(bombs.get(bombId));
        }

        @Override
        public void addBomb(Bomb bomb) {
            bombs.put(bomb.id(), bomb);
        }

        @Override
        public void removeBomb(int bombId) {
            bombs.remove(bombId);
        }

        @Override
        public void destroyBlock(TilePosition position) {
            tiles.put(position, TileType.EMPTY);
        }

        @Override
        public Collection<String> alivePlayersAt(TilePosition position) {
            return playerPositions.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(position))
                    .map(Map.Entry::getKey)
                    .filter(alivePlayers::contains)
                    .toList();
        }

        @Override
        public void killPlayer(String playerId, String killerPlayerId) {
            alivePlayers.remove(playerId);
        }
    }
}

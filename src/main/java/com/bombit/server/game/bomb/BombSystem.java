package com.bombit.server.game.bomb;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.model.Direction;

import com.bombit.server.game.event.BlockDestroyedEvent;
import com.bombit.server.game.event.BombExplodedEvent;
import com.bombit.server.game.event.BombPlacedEvent;
import com.bombit.server.game.event.GameEventSink;
import com.bombit.server.game.event.PlayerDiedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Luật đặt và kích nổ bom phía server. Được gọi từ Game Loop. */
public final class BombSystem {
    private final AtomicInteger nextBombId;
    private final long fuseMillis;
    private final GameEventSink eventSink;

    public BombSystem(long fuseMillis, GameEventSink eventSink) {
        this(fuseMillis, eventSink, 1);
    }

    BombSystem(long fuseMillis, GameEventSink eventSink, int firstBombId) {
        if (fuseMillis <= 0) {
            throw new IllegalArgumentException("Fuse must be positive");
        }
        this.fuseMillis = fuseMillis;
        this.eventSink = Objects.requireNonNull(eventSink);
        this.nextBombId = new AtomicInteger(firstBombId);
    }

    public BombPlacementResult plantBomb(String playerId, long nowMillis, BombWorld world) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(world);

        if (!world.playerExists(playerId)) {
            return BombPlacementResult.failure(BombError.PLAYER_NOT_FOUND);
        }
        if (!world.isPlayerAlive(playerId)) {
            return BombPlacementResult.failure(BombError.PLAYER_NOT_ALIVE);
        }

        TilePosition position = world.playerPosition(playerId);
        if (world.tileType(position) != TileType.EMPTY) {
            return BombPlacementResult.failure(BombError.INVALID_CELL);
        }
        if (world.bombAt(position).isPresent()) {
            return BombPlacementResult.failure(BombError.CELL_OCCUPIED);
        }
        if (world.activeBombCount(playerId) >= world.playerBombLimit(playerId)) {
            return BombPlacementResult.failure(BombError.BOMB_LIMIT_REACHED);
        }

        Bomb bomb = new Bomb(
                nextBombId.getAndIncrement(),
                playerId,
                position,
                nowMillis,
                fuseMillis,
                world.playerBombRange(playerId));
        world.addBomb(bomb);
        eventSink.publish(new BombPlacedEvent(
                nowMillis,
                bomb.id(),
                bomb.ownerPlayerId(),
                bomb.position(),
                bomb.fuseMillis(),
                bomb.explodeAtMillis(),
                bomb.range()));
        return BombPlacementResult.success(bomb);
    }

    public ExplosionResult explodeBomb(int bombId, long nowMillis, BombWorld world) {
        return explodeBombs(List.of(bombId), nowMillis, world);
    }

    /** One tick's roots share block barriers, including all recursively chained bombs. */
    public ExplosionResult explodeBombs(Collection<Integer> bombIds, long nowMillis, BombWorld world) {
        Objects.requireNonNull(world);
        Queue<Bomb> pending = new ArrayDeque<>();
        Set<Integer> processedBombIds = new HashSet<>();
        List<Integer> explodedBombIds = new ArrayList<>();
        Set<TilePosition> affectedCells = new LinkedHashSet<>();
        Set<TilePosition> destroyedBlocks = new LinkedHashSet<>();
        Set<String> killedPlayerIds = new LinkedHashSet<>();
        java.util.Map<String,String> hits=new java.util.LinkedHashMap<>();

        for (int bombId : bombIds) world.bombById(bombId).ifPresent(pending::add);
        while (!pending.isEmpty()) {
            Bomb bomb = pending.remove();
            if (!processedBombIds.add(bomb.id()) || !bomb.markExploded()) {
                continue;
            }

            world.removeBomb(bomb.id());
            explodedBombIds.add(bomb.id());
            Set<TilePosition> bombCells = calculateAffectedCells(bomb, world, destroyedBlocks);
            affectedCells.addAll(bombCells);

            for (TilePosition cell : bombCells) {
                world.bombAt(cell).ifPresent(chainedBomb -> {
                    if (!processedBombIds.contains(chainedBomb.id())) {
                        pending.add(chainedBomb);
                    }
                });
                for (String playerId : world.alivePlayersAt(cell)) {
                    hits.putIfAbsent(playerId,bomb.ownerPlayerId());
                }
            }
            eventSink.publish(new BombExplodedEvent(nowMillis, bomb.id(), bomb.position(), Set.copyOf(bombCells)));
        }

        // Resolve once after all rays: a respawn cannot be hit again by this same explosion batch.
        hits.forEach((playerId,killer)->{
            world.damagePlayer(playerId,killer);
            if(!world.isPlayerAlive(playerId)){
                killedPlayerIds.add(playerId);
                eventSink.publish(new PlayerDiedEvent(nowMillis,playerId,killer,"EXPLOSION"));
            }
        });
        for (TilePosition block : destroyedBlocks) {
            eventSink.publish(new BlockDestroyedEvent(nowMillis, block));
        }
        return new ExplosionResult(
                List.copyOf(explodedBombIds),
                Set.copyOf(affectedCells),
                Set.copyOf(destroyedBlocks),
                Set.copyOf(killedPlayerIds));
    }

    private Set<TilePosition> calculateAffectedCells(
            Bomb bomb,
            BombWorld world,
            Set<TilePosition> destroyedBlocks) {
        Set<TilePosition> cells = new LinkedHashSet<>();
        cells.add(bomb.position());

        for (Direction direction : Direction.values()) {
            for (int distance = 1; distance <= bomb.range(); distance++) {
                TilePosition position = bomb.position().move(direction, distance);
                // A block destroyed earlier in this batch still stops every ray in the same batch.
                TileType tileType = destroyedBlocks.contains(position) ? TileType.BREAKABLE_BLOCK : world.tileType(position);
                if (tileType == TileType.HARD_WALL || tileType == TileType.OUT_OF_BOUNDS) {
                    break;
                }

                cells.add(position);
                if (tileType == TileType.BREAKABLE_BLOCK) {
                    if (destroyedBlocks.add(position)) {
                        world.destroyBlock(position);
                    }
                    break;
                }
            }
        }
        return cells;
    }
}

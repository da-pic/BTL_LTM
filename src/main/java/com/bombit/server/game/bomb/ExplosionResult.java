package com.bombit.server.game.bomb;

import com.bombit.server.game.model.TilePosition;

import java.util.List;
import java.util.Set;

public record ExplosionResult(
        List<Integer> explodedBombIds,
        Set<TilePosition> affectedCells,
        Set<TilePosition> destroyedBlocks,
        Set<String> killedPlayerIds) {
    public ExplosionResult {
        explodedBombIds = List.copyOf(explodedBombIds);
        affectedCells = Set.copyOf(affectedCells);
        destroyedBlocks = Set.copyOf(destroyedBlocks);
        killedPlayerIds = Set.copyOf(killedPlayerIds);
    }
}

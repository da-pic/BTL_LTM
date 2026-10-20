package com.bombit.server.game.bomb;

import java.util.List;
import java.util.Set;

public record ExplosionResult(
        List<Integer> explodedBombIds,
        Set<TilePosition> affectedCells,
        Set<TilePosition> destroyedBlocks,
        Set<String> killedPlayerIds) {
}

package com.bombit.client.game.bomb;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Set;

public record ExplosionView(
        int bombId,
        Set<TilePosition> affectedCells,
        long startedAtMillis,
        long visibleUntilMillis) {
}

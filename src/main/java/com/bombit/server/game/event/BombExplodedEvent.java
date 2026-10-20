package com.bombit.server.game.event;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Set;

public record BombExplodedEvent(
        long occurredAtMillis,
        int bombId,
        TilePosition position,
        Set<TilePosition> affectedCells) implements GameEvent {
}

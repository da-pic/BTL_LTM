package com.bombit.server.game.event;

import com.bombit.server.game.model.TilePosition;

import java.util.Set;

public record BombExplodedEvent(
        long occurredAtMillis,
        int bombId,
        TilePosition position,
        Set<TilePosition> affectedCells) implements GameEvent {
    public BombExplodedEvent { affectedCells = Set.copyOf(affectedCells); }
}

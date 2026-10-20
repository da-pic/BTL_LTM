package com.bombit.server.game.event;

import com.bombit.server.game.bomb.TilePosition;

public record BombPlacedEvent(
        long occurredAtMillis,
        int bombId,
        String ownerPlayerId,
        TilePosition position,
        long fuseMillis,
        long explodeAtMillis,
        int range) implements GameEvent {
}

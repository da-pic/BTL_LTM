package com.bombit.server.game.event;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.item.ItemType;

public record ItemSpawnedEvent(
        long occurredAtMillis,
        int itemId,
        ItemType itemType,
        TilePosition position) implements GameEvent {
}

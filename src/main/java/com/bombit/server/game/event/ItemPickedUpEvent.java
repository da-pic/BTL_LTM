package com.bombit.server.game.event;

import com.bombit.server.game.item.ItemType;

public record ItemPickedUpEvent(
        long occurredAtMillis,
        int itemId,
        String playerId,
        ItemType itemType) implements GameEvent {
}

package com.bombit.server.game.event;

import com.bombit.server.game.model.TilePosition;

public record BlockDestroyedEvent(long occurredAtMillis, TilePosition position) implements GameEvent {
}

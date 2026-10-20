package com.bombit.server.game.event;

import com.bombit.server.game.bomb.TilePosition;

public record BlockDestroyedEvent(long occurredAtMillis, TilePosition position) implements GameEvent {
}

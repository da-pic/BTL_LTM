package com.bombit.server.game.event;

/** Interface đánh dấu các sự kiện trò chơi trước khi tuần tự hóa theo giao thức. */
public sealed interface GameEvent permits BombPlacedEvent, BombExplodedEvent, BlockDestroyedEvent,
        PlayerDiedEvent, ItemSpawnedEvent, ItemPickedUpEvent {
    long occurredAtMillis();
}

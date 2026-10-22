package com.bombit.common.dto;

import com.bombit.server.game.event.*;

/** Internal immutable handoff, captured on the game thread; serialized only by the snapshot worker. */
public record GameNotice(long eventId,GameEvent event) {
    public GameNotice {
        if(eventId<=0 || !(event instanceof BombPlacedEvent || event instanceof BombExplodedEvent
                || event instanceof ItemSpawnedEvent || event instanceof ItemPickedUpEvent || event instanceof PlayerDiedEvent))
            throw new IllegalArgumentException("Invalid gameplay notice");
    }
}

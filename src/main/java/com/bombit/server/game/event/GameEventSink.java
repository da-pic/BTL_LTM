package com.bombit.server.game.event;

@FunctionalInterface
public interface GameEventSink {
    void publish(GameEvent event);
}

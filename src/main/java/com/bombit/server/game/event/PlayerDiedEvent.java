package com.bombit.server.game.event;

public record PlayerDiedEvent(
        long occurredAtMillis,
        String playerId,
        String killerPlayerId,
        String cause) implements GameEvent {
}

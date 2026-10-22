package com.bombit.server.game.bomb;

/** Immutable command outcome for an application-owned queue; never exposes mutable Bomb. */
public record BombCommandResult(int playerId, BombError error, Integer bombId) { }

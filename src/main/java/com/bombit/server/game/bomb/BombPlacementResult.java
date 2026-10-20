package com.bombit.server.game.bomb;

import java.util.Optional;

public record BombPlacementResult(Bomb bomb, BombError error) {
    public static BombPlacementResult success(Bomb bomb) {
        return new BombPlacementResult(bomb, BombError.NONE);
    }

    public static BombPlacementResult failure(BombError error) {
        if (error == BombError.NONE) {
            throw new IllegalArgumentException("Failure must have an error");
        }
        return new BombPlacementResult(null, error);
    }

    public boolean succeeded() {
        return error == BombError.NONE;
    }

    public Optional<Bomb> placedBomb() {
        return Optional.ofNullable(bomb);
    }
}

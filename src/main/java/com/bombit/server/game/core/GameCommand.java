package com.bombit.server.game.core;

import java.util.Objects;

public final class GameCommand {

    private final int playerId;
    private final Player.Direction direction;
    private final boolean moving;

    public GameCommand(int playerId,
                       Player.Direction direction,
                       boolean moving) {

        if (playerId <= 0) {
            throw new IllegalArgumentException(
                    "playerId phai lon hon 0");
        }

        this.playerId = playerId;
        this.direction = Objects.requireNonNull(
                direction, "direction khong duoc null");
        this.moving = moving;
    }

    public int getPlayerId() {
        return playerId;
    }

    public Player.Direction getDirection() {
        return direction;
    }

    public boolean isMoving() {
        return moving;
    }

    @Override
    public String toString() {
        return "GameCommand{" +
                "playerId=" + playerId +
                ", direction=" + direction +
                ", moving=" + moving +
                '}';
    }
}

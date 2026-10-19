package com.bombit.server.game.movement;

import java.util.Objects;

import com.bombit.server.game.core.GameCommand;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

public class MovementSystem {

    //Giới hạn deltatime cho mỗi lần cập nhật
    private static final double MAX_DELTA_TIME_SECONDS = 0.1;

    private final CollisionSystem collisionSystem;

    public MovementSystem() {
        this(new CollisionSystem());
    }

    public MovementSystem(CollisionSystem collisionSystem) {
        this.collisionSystem = Objects.requireNonNull(
                collisionSystem,
                "collisionSystem khong duoc null"
        );
    }

    //Áp dụng command vào trạng thái input của Player
    public boolean applyCommand(
            GameState state, GameCommand command) {

        Objects.requireNonNull(state, "state khong duoc null");
        Objects.requireNonNull(command, "command khong duoc null");

        //Chỉ xử lý lệnh khi trận đấu đang chạy (RUNNING)
        if (state.getMatchState() != GameState.MatchState.RUNNING) {
            return false;
        }

        Player player = state.getPlayer(command.getPlayerId());

        //Bỏ qua lệnh của Player ko tồn tại hoặc đã chết
        if (player == null || !player.isAlive()) {
            return false;
        }

        player.setDirection(command.getDirection());
        player.setMoving(command.isMoving());

        return true;
    }

    //Cập nhật chuyển động của tất cả Player trong 1 tick
    public void update(
            GameState state, double deltaTimeSeconds) {

        Objects.requireNonNull(state, "state khong duoc null");

        if (!Double.isFinite(deltaTimeSeconds)
                || deltaTimeSeconds < 0) {
            throw new IllegalArgumentException(
                    "deltaTime phai la so huu han va khong am");
        }

        if (state.getMatchState() != GameState.MatchState.RUNNING
                || deltaTimeSeconds == 0) {
            return;
        }

        //Hạn chế nhảy vị trí nếu 1 tick chậm bất thường
        double dt = Math.min(
                deltaTimeSeconds,
                MAX_DELTA_TIME_SECONDS
        );

        for (Player player : state.getPlayers()) {

            if (!player.isAlive() || !player.isMoving()) {
                continue;
            }

            //Quãng đường = tôc độ * thời gian
            double distance = player.getSpeed() * dt;

            if (!Double.isFinite(distance)) {
                throw new IllegalStateException(
                        "Quang duong di chuyen khong hop le");
            }

            double dx = 0;
            double dy = 0;

            switch (player.getDirection()) {
                case UP:
                    dy = -distance;
                    break;

                case DOWN:
                    dy = distance;
                    break;

                case LEFT:
                    dx = -distance;
                    break;

                case RIGHT:
                    dx = distance;
                    break;
            }

            //CollisionSystem sẽ tự kiểm tra từng bước di chuyển
            collisionSystem.move(
                    player,
                    state.getMap(),
                    dx,
                    dy
            );
        }
    }
}
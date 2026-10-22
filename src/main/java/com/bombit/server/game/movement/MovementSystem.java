package com.bombit.server.game.movement;

import java.util.Objects;

import com.bombit.server.game.core.GameCommand;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

/**
 * Xử lý input và chuyển động ở phía server theo hai giai đoạn.
 * applyCommand chỉ thay hướng/moving; update mới tính quãng đường và kiểm tra collision.
 * Nhiều command trong một tick không tự nhân tốc độ vì dịch chuyển chỉ thực hiện ở update.
 */
public class MovementSystem {

    //Giới hạn deltatime cho mỗi lần cập nhật
    private static final double MAX_DELTA_TIME_SECONDS = 0.1;

    private final CollisionSystem collisionSystem;

    // Mục đích: Khởi tạo MovementSystem và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    public MovementSystem() {
        this(new CollisionSystem());
    }

    // Mục đích: Khởi tạo MovementSystem và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    public MovementSystem(CollisionSystem collisionSystem) {
        this.collisionSystem = Objects.requireNonNull(
                collisionSystem,
                "collisionSystem khong duoc null"
        );
    }

    //Áp dụng command vào trạng thái input của Player
    // Mục đích: Xác thực trận RUNNING và player còn sống trước khi đổi ý định. playerId cần được tầng handler đối chiếu session, không tin ID client tự khai.
    public boolean applyCommand(
            GameState state, GameCommand command) {

        Objects.requireNonNull(state, "state khong duoc null");
        Objects.requireNonNull(command, "command khong duoc null");

        //Chỉ xử lý lệnh khi trận đấu đang chạy (RUNNING)
        if (state.getMatchState() != GameState.MatchState.RUNNING) {
            return false;
        }

        if (!command.isMovement()) return false;
        Player player = state.getPlayer(command.getPlayerId());

        //Bỏ qua lệnh của Player ko tồn tại hoặc đã chết
        if (player == null || !player.isAlive()) {
            return false;
        }

        // Mẫu trùng hoặc đến muộn không được mô phỏng lại, tránh client tăng tốc bằng gửi lại.
        if (command.getSequence() > 0) {
            if (command.getSequence() <= player.getLastProcessedInput()) return false;
            player.acknowledgeInput(command.getSequence());
        } else if (player.isSampledInput() && !(command instanceof com.bombit.server.game.core.StopMovingCommand)) return false;
        player.setDirection(command.direction());
        player.setMoving(command.isMoving());

        return true;
    }

    //Cập nhật chuyển động của tất cả Player trong 1 tick
    // Mục đích: Tính distance = speed * dt cho player đang đi và còn sống, rồi giao collision quyết định vị trí. dt bị chặn tối đa 0,1 giây để tránh bước nhảy do trễ.
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

        collisionSystem.setEntities(state.getEntities());

        for (Player player : state.getPlayers()) {

            if (!player.isAlive() || !player.isMoving() || !player.canAdvance()) {
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

            switch (player.direction()) {
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

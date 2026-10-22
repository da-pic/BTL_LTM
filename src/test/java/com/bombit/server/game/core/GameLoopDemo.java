package com.bombit.server.game.core;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.GameStateUpdate;

/**
 * Demo vòng đời GameLoop: khởi tạo trận, chạy luồng, gửi MOVE và STOP rồi dừng.
 * Dùng số command đã xử lý để chờ tiến trình, sau đó kiểm tra kết quả khi loop đã dừng.
 * Các khoảng ngủ phụ thuộc lịch chạy thread; không coi đây là benchmark thời gian chính xác.
 */
public class GameLoopDemo {

    // Mục đích: Chạy các bước kiểm tra của GameLoopDemo; xem chú thích tại từng kịch bản và kết quả console.
    public static void main(String[] args)
            throws InterruptedException {

        // 1. Tao map 12 cot, 8 hang.
        GameMap map = new GameMap(12, 8, 32);

        // 2. Tao tuong bao quanh map.
        for (int x = 0; x < map.getWidthInTiles(); x++) {
            map.setTile(x, 0, TileType.HARD_WALL);

            map.setTile(
                    x,
                    map.getHeightInTiles() - 1,
                    TileType.HARD_WALL
            );
        }

        for (int y = 0; y < map.getHeightInTiles(); y++) {
            map.setTile(0, y, TileType.HARD_WALL);

            map.setTile(
                    map.getWidthInTiles() - 1,
                    y,
                    TileType.HARD_WALL
            );
        }

        // 3. Tao GameState va Player.
        GameState state = new GameState(map);

        Player p1 = new Player(
                1, 32, 64, 24, 24, 150);

        state.addPlayer(p1);
        state.startMatch();

        // 4. Tao GameLoop.
        GameLoop gameLoop = new GameLoop(state);

        double startX = p1.getX();

        boolean moveSubmitted = false;
        boolean moveProcessed = false;
        boolean stopSubmitted = false;
        boolean stopProcessed = false;

        try {
            // 5. Khoi dong GameLoop.
            gameLoop.start();

            // Update dau tien phai la FULL_STATE.
            GameStateUpdate initial =
                    gameLoop.takeOutboundUpdate();

            System.out.println(
                    "Initial type: " + initial.getType());

            System.out.println(
                    "Initial map tiles: "
                            + initial.mapTiles().size());

            System.out.println(
                    "Initial players: "
                            + initial.getPlayers().size());

            // 6. Gui lenh di sang phai.
            moveSubmitted = gameLoop.submitCommand(
                    new MoveCommand(1, Direction.RIGHT)
            );

            moveProcessed = waitForCommands(
                    gameLoop, 1, 1000);

            // Cho Player di chuyen trong 250 ms.
            Thread.sleep(250);

            // 7. Gui lenh dung.
            stopSubmitted = gameLoop.submitCommand(
                    new StopMovingCommand(1, Direction.RIGHT, 0)
            );

            stopProcessed = waitForCommands(
                    gameLoop, 2, 1000);

        } finally {
            // Luon dung GameLoop khi kiem thu ket thuc.
            gameLoop.stop();
        }

        // 8. Kiem tra trang thai sau khi GameLoop dung.
        double endX = p1.getX();

        int deltaCount = 0;
        int playerChangeCount = 0;
        GameStateUpdate update;

        while ((update = gameLoop.pollOutboundUpdate()) != null) {

            if (!update.isFullState()) {
                deltaCount++;

                playerChangeCount +=
                        update.getPlayerChanges().size();
            }
        }

        System.out.println(
                "=== TEST GAME LOOP + DELTA ===");

        System.out.println(
                "Gui lenh di chuyen: " + moveSubmitted);

        System.out.println(
                "Da xu ly lenh di chuyen: " + moveProcessed);

        System.out.println(
                "Gui lenh dung: " + stopSubmitted);

        System.out.println(
                "Da xu ly lenh dung: " + stopProcessed);

        System.out.println("X ban dau: " + startX);
        System.out.println("X cuoi: " + endX);

        System.out.println(
                "Player da di chuyen: " + (endX > startX));

        System.out.println(
                "Player da dung: " + !p1.isMoving());

        System.out.println(
                "So tick: " + gameLoop.getTick());

        System.out.println(
                "So command da xu ly: "
                        + gameLoop.getProcessedCommandCount());

        System.out.println(
                "So delta update: " + deltaCount);

        System.out.println(
                "Tong player-change: " + playerChangeCount);

        System.out.println(
                "Outbound queue full count: "
                        + gameLoop.getOutboundQueueFullCount());

        System.out.println(
                "GameLoop dang chay: " + gameLoop.isRunning());

        System.out.println("=== KET THUC KIEM THU ===");
    }

    // Mục đích: Chờ có giới hạn để loop xử lý đủ command trước khi kiểm tra; tránh treo vô hạn khi thread không tiến triển.
    private static boolean waitForCommands(
            GameLoop gameLoop,
            long expectedCount,
            long timeoutMillis)
            throws InterruptedException {

        long deadline = System.nanoTime()
                + timeoutMillis * 1_000_000L;

        while (gameLoop.getProcessedCommandCount()
                < expectedCount
                && System.nanoTime() < deadline) {

            Thread.sleep(1);
        }

        return gameLoop.getProcessedCommandCount()
                >= expectedCount;
    }
}

package com.bombit.server.game.core;

public class GameStateDemo {

    public static void main(String[] args) {

        // 1. Tao ban do 8 cot, 6 hang, moi o 32 pixel
        GameMap map = new GameMap(17, 15, 32);

        // 2. Tao tuong bao quanh ban do
        for (int x = 0; x < map.getWidthInTiles(); x++) {
            map.setTile(x, 0, GameMap.TileType.HARD_WALL);
            map.setTile(
                    x,
                    map.getHeightInTiles() - 1,
                    GameMap.TileType.HARD_WALL
            );
        }

        for (int y = 0; y < map.getHeightInTiles(); y++) {
            map.setTile(0, y, GameMap.TileType.HARD_WALL);
            map.setTile(
                    map.getWidthInTiles() - 1,
                    y,
                    GameMap.TileType.HARD_WALL
            );
        }

        // 3. Dat mot block co the pha tai cot 3, hang 2
        map.setTile(3, 2, GameMap.TileType.BREAKABLE_BLOCK);

        System.out.println("=== KIEM TRA GAME MAP ===");
        System.out.println(
                "So cot: " + map.getWidthInTiles());
        System.out.println(
                "So hang: " + map.getHeightInTiles());
        System.out.println(
                "Chieu rong pixel: " + map.getPixelWidth());
        System.out.println(
                "Chieu cao pixel: " + map.getPixelHeight());

        System.out.println(
                "O (3,2): " + map.getTile(3, 2));
        System.out.println(
                "O (1,1) di qua duoc: "
                        + map.isWalkable(1, 1));
        System.out.println(
                "O (0,0) di qua duoc: "
                        + map.isWalkable(0, 0));
        System.out.println(
                "O ngoai ban do di qua duoc: "
                        + map.isWalkable(-1, 1));

        // 4. Tao GameState
        GameState state = new GameState(map);

        // 5. Tao hai nguoi choi
        Player p1 = new Player(1, 32, 32, 24, 24, 150);
        Player p2 = new Player(2, 64, 32, 24, 24, 150);

        System.out.println("\n=== KIEM TRA PLAYER ===");
        System.out.println("Them P1: " + state.addPlayer(p1));
        System.out.println("Them P2: " + state.addPlayer(p2));
        System.out.println(
                "Them lai P1: " + state.addPlayer(p1));
        System.out.println(
                "Tong so Player: " + state.getPlayerCount());
        System.out.println(
                "Tim Player 1: " + state.getPlayer(1));
        System.out.println(
                "Tim Player 99: " + state.getPlayer(99));

        // 6. Cap nhat trang thai P1
        p1.setDirection(Player.Direction.RIGHT);
        p1.setMoving(true);
        p1.setPosition(40, 32);

        System.out.println("\n=== SAU KHI CAP NHAT P1 ===");
        System.out.println(state.getPlayer(1));

        // 7. Kiem tra pha block
        System.out.println("\n=== KIEM TRA BLOCK ===");
        System.out.println(
                "Pha block lan 1: " + map.destroyBlock(3, 2));
        System.out.println(
                "Loai o sau khi pha: " + map.getTile(3, 2));
        System.out.println(
                "Pha block lan 2: " + map.destroyBlock(3, 2));

        // 8. Kiem tra trang thai tran dau
        System.out.println("\n=== KIEM TRA GAME STATE ===");
        System.out.println("Ban dau: " + state.getMatchState());

        state.startMatch();
        System.out.println("Sau startMatch: " + state.getMatchState());

        state.finishMatch();
        System.out.println("Sau finishMatch: " + state.getMatchState());
        System.out.println("P1 dang di chuyen: " + p1.isMoving());

        System.out.println("\n=== KET THUC KIEM THU ===");
    }
}

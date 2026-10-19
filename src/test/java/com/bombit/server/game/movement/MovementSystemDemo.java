package com.bombit.server.game.movement;

import com.bombit.server.game.core.GameCommand;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

public class MovementSystemDemo {

    public static void main(String[] args) {

        // 1. Tao map 8 cot, 6 hang; moi tile 32 pixel
        GameMap map = new GameMap(8, 6, 32);

        // 2. Tao tuong bao quanh map
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

        // Tuong cung nam tai cot 3, hang 2
        map.setTile(3, 2, GameMap.TileType.HARD_WALL);

        // Block co the pha tai cot 3, hang 3
        map.setTile(3, 3, GameMap.TileType.BREAKABLE_BLOCK);

        // 3. Tao hai Player
        Player p1 = new Player(1, 32, 64, 24, 24, 150);
        Player p2 = new Player(2, 32, 96, 24, 24, 150);

        // 4. Tao GameState va them Player
        GameState state = new GameState(map);
        state.addPlayer(p1);
        state.addPlayer(p2);
        state.startMatch();

        // 5. Tao cac he thong
        CollisionSystem collision = new CollisionSystem();
        MovementSystem movement = new MovementSystem(collision);

        System.out.println("=== TEST VA CHAM ===");

        System.out.println(
                "P1 dung sat tuong tai x=72: "
                        + collision.canOccupy(map, p1, 72, 64));

        System.out.println(
                "P1 bi chan tai x=73: "
                        + !collision.canOccupy(map, p1, 73, 64));

        System.out.println(
                "P2 bi block chan tai x=73: "
                        + !collision.canOccupy(map, p2, 73, 96));

        System.out.println(
                "Vi tri ngoai map hop le: "
                        + collision.canOccupy(map, p1, -1, 64));

        // 6. Cho P1 di sang phai
        System.out.println("\n=== TEST PLAYER 1 ===");

        movement.applyCommand(
                state,
                new GameCommand(
                        1, Player.Direction.RIGHT, true)
        );

        for (int i = 0; i < 10; i++) {
            movement.update(state, 0.1);
        }

        System.out.println("P1 sau khi gap tuong: " + p1);
        System.out.println(
                "P1 khong xuyen tuong: " + (p1.getX() <= 72));

        // 7. Cho P2 di vao block
        System.out.println("\n=== TEST PLAYER 2 ===");

        movement.applyCommand(
                state,
                new GameCommand(
                        2, Player.Direction.RIGHT, true)
        );

        for (int i = 0; i < 10; i++) {
            movement.update(state, 0.1);
        }

        System.out.println("P2 truoc khi pha block: " + p2);

        // 8. Pha block bang GameMap
        boolean destroyed = map.destroyBlock(3, 3);

        System.out.println("Pha block thanh cong: " + destroyed);

        // Cap nhat tiep: P2 van dang yeu cau di sang phai
        movement.update(state, 0.1);

        System.out.println("P2 sau khi pha block: " + p2);
        System.out.println(
                "P2 da di qua vi tri block: " + (p2.getX() > 72));

        // 9. Nha phim, yeu cau dung lai
        System.out.println("\n=== TEST DUNG DI CHUYEN ===");

        movement.applyCommand(
                state,
                new GameCommand(
                        2, Player.Direction.RIGHT, false)
        );

        double oldX = p2.getX();
        movement.update(state, 0.1);

        System.out.println("P2 x truoc update: " + oldX);
        System.out.println("P2 x sau update: " + p2.getX());
        System.out.println(
                "P2 dung lai: " + (oldX == p2.getX()));

        System.out.println("\n=== KET THUC KIEM THU ===");
    }
}

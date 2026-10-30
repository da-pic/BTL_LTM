package com.bombit.server.game.mode.shadow;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.util.List;

/** Layout Shadow v1 đối xứng; spawn/trạm là nền trống, không thêm loại tile vật cản giả. */
public final class ShadowArena {
    public static final String LAYOUT_ID = "shadow-relay-arena-v1";
    public static final int WIDTH = 17, HEIGHT = 15, TILE_PIXELS = 32;
    // Thứ tự slot: A trái trên, B phải trên, A trái dưới, B phải dưới.
    public static final List<TilePosition> SPAWNS = List.of(
            new TilePosition(1, 1), new TilePosition(15, 1),
            new TilePosition(1, 13), new TilePosition(15, 13));
    public static final List<TilePosition> STATIONS = List.of(
            new TilePosition(8, 3), new TilePosition(8, 7), new TilePosition(8, 11));

    private static final List<String> ROWS = List.of(
            "#################",
            "#1..+.+...+.+..2#",
            "#.#.#.#...#.#.#.#",
            "#.......T.......#",
            "#.#+#+#...#+#+#.#",
            "#..+.+.....+.+..#",
            "#+#+#+#...#+#+#+#",
            "#.......T.......#",
            "#+#+#+#...#+#+#+#",
            "#..+.+.....+.+..#",
            "#.#+#+#...#+#+#.#",
            "#.......T.......#",
            "#.#.#.#...#.#.#.#",
            "#3..+.+...+.+..4#",
            "#################");

    private ShadowArena() { }

    /** Mỗi trận nhận map mutable riêng; tuyệt đối không cache một GameMap dùng chung giữa các phòng. */
    public static GameMap create() {
        GameMap map = new GameMap(WIDTH, HEIGHT, TILE_PIXELS);
        for (int y = 0; y < HEIGHT; y++) {
            String row = ROWS.get(y);
            if (row.length() != WIDTH) throw new IllegalStateException("Invalid Shadow layout row");
            for (int x = 0; x < WIDTH; x++) {
                TileType tile = switch (row.charAt(x)) {
                    case '#' -> TileType.HARD_WALL;
                    case '+' -> TileType.BREAKABLE_BLOCK;
                    case '.', 'T', '1', '2', '3', '4' -> TileType.EMPTY;
                    default -> throw new IllegalStateException("Unknown Shadow layout cell");
                };
                map.setTile(x, y, tile);
            }
        }
        return map;
    }

    public static TilePosition spawn(int slot) {
        if (slot < 1 || slot > 4) throw new IllegalArgumentException("Shadow slot must be 1..4");
        return SPAWNS.get(slot - 1);
    }
}

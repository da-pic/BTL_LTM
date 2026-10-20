package com.bombit.server.game.bomb;

/** Tọa độ ô bất biến dùng cho cơ chế bom và vật phẩm. */
public record TilePosition(int x, int y) {
    public TilePosition move(Direction direction, int distance) {
        return new TilePosition(x + direction.deltaX() * distance, y + direction.deltaY() * distance);
    }
}

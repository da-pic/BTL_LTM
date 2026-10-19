
package com.bombit.server.game.core;

import java.util.Arrays;
import java.util.Objects;

public class GameMap {

    //Các loại ô
    public enum TileType {
        EMPTY,
        HARD_WALL,
        BREAKABLE_BLOCK
    }

    //Kich thước bản đồ (Ô)
    private final int widthInTiles;
    private final int heightInTiles;

    //Kích thước 1 ô (pixel)
    private final int tileSize;

    //Ma trận bản đồ ([y: hàng][x: cột])
    private final TileType[][] tiles;

    public GameMap(int widthInTiles, int heightInTiles,
                   int tileSize) {

        if (widthInTiles <= 0 || heightInTiles <= 0
                || tileSize <= 0) {
            throw new IllegalArgumentException(
                    "Kich thuoc ban do va tileSize phai lon hon 0");
        }

        this.widthInTiles = widthInTiles;
        this.heightInTiles = heightInTiles;
        this.tileSize = tileSize;

        tiles = new TileType[heightInTiles][widthInTiles];

        //Mặc định là ô trống
        for (int y = 0; y < heightInTiles; y++) {
            Arrays.fill(tiles[y], TileType.EMPTY);
        }
    }

    public int getWidthInTiles() {
        return widthInTiles;
    }

    public int getHeightInTiles() {
        return heightInTiles;
    }

    public int getTileSize() {
        return tileSize;
    }

    public int getPixelWidth() {
        return widthInTiles * tileSize;
    }

    public int getPixelHeight() {
        return heightInTiles * tileSize;
    }

    //Kiểm tra toạ độ có hợp lệ ko (Nằm trong map hay ko)
    public boolean isInsideMap(int tileX, int tileY) {
        return tileX >= 0 && tileX < widthInTiles
                && tileY >= 0 && tileY < heightInTiles;
    }

    //Lấy loại ô tạo toạ độ
    public TileType getTile(int tileX, int tileY) {
        checkCoordinates(tileX, tileY);
        return tiles[tileY][tileX];
    }

    //Đặt loại ô tại toạ độ (Khởi tạo map)
    public void setTile(int tileX, int tileY, TileType type) {
        checkCoordinates(tileX, tileY);
        tiles[tileY][tileX] = Objects.requireNonNull(
                type, "TileType khong duoc null");
    }

    //Check xem có thể đi qua ko (Chỉ ô EMPTY)
    public boolean isWalkable(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            return false;
        }

        return tiles[tileY][tileX] == TileType.EMPTY;
    }

    //Kiểm tra xem có thể phá block này ko (Chỉ BREAKABLE_BLOCK)
    public boolean destroyBlock(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            return false;
        }

        if (tiles[tileY][tileX] == TileType.BREAKABLE_BLOCK) {
            tiles[tileY][tileX] = TileType.EMPTY;
            return true;
        }

        return false;
    }

    private void checkCoordinates(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            throw new IndexOutOfBoundsException(
                    "Toa do tile nam ngoai ban do: ("
                            + tileX + ", " + tileY + ")");
        }
    }
}

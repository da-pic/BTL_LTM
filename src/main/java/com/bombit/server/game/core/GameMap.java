
package com.bombit.server.game.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Bản đồ nền dạng lưới tiles[y][x], tách biệt với bomb/item và player.
 * Tọa độ tile là chỉ số ô; tileSize đổi từ ô sang pixel để tính hitbox.
 * EMPTY cho đi qua, HARD_WALL và BREAKABLE_BLOCK chặn movement.
 * Lớp không có khóa: khi trận chạy, thay đổi map phải thuộc luồng GameLoop.
 */
public class GameMap {

    //Các loại ô
    @Deprecated
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
    private final com.bombit.server.game.model.TileType[][] tiles;

    // Mục đích: Khởi tạo GameMap và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
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

        tiles = new com.bombit.server.game.model.TileType[heightInTiles][widthInTiles];

        //Mặc định là ô trống
        for (int y = 0; y < heightInTiles; y++) {
            Arrays.fill(tiles[y], com.bombit.server.game.model.TileType.EMPTY);
        }
    }

    // Mục đích: Đọc WidthInTiles để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getWidthInTiles() {
        return widthInTiles;
    }

    // Mục đích: Đọc HeightInTiles để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getHeightInTiles() {
        return heightInTiles;
    }

    // Mục đích: Đọc TileSize để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getTileSize() {
        return tileSize;
    }

    // Mục đích: Đọc PixelWidth để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getPixelWidth() {
        return widthInTiles * tileSize;
    }

    // Mục đích: Đọc PixelHeight để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getPixelHeight() {
        return heightInTiles * tileSize;
    }

    //Kiểm tra toạ độ có hợp lệ ko (Nằm trong map hay ko)
    // Mục đích: Đọc InsideMap để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isInsideMap(int tileX, int tileY) {
        return tileX >= 0 && tileX < widthInTiles
                && tileY >= 0 && tileY < heightInTiles;
    }

    //Lấy loại ô tạo toạ độ
    // Mục đích: Đọc Tile để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    @Deprecated public TileType getTile(int tileX, int tileY) {
        checkCoordinates(tileX, tileY);
        return TileType.valueOf(tiles[tileY][tileX].name());
    }

    //Đặt loại ô tại toạ độ (Khởi tạo map)
    @Deprecated public void setTile(int tileX, int tileY, TileType type) {
        checkCoordinates(tileX, tileY);
        tiles[tileY][tileX] = com.bombit.server.game.model.TileType.valueOf(Objects.requireNonNull(type).name());
    }

    //Check xem có thể đi qua ko (Chỉ ô EMPTY)
    // Mục đích: Đọc Walkable để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isWalkable(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            return false;
        }

        return tiles[tileY][tileX] == com.bombit.server.game.model.TileType.EMPTY;
    }

    //Kiểm tra xem có thể phá block này ko (Chỉ BREAKABLE_BLOCK)
    public boolean destroyBlock(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            return false;
        }

        if (tiles[tileY][tileX] == com.bombit.server.game.model.TileType.BREAKABLE_BLOCK) {
            tiles[tileY][tileX] = com.bombit.server.game.model.TileType.EMPTY;
            return true;
        }

        return false;
    }

    /** API model chung: ngoài map trả sentinel, không truy cập mảng ngoài giới hạn. */
    public com.bombit.server.game.model.TileType tileAt(int x,int y) {
        return isInsideMap(x,y) ? tiles[y][x] : com.bombit.server.game.model.TileType.OUT_OF_BOUNDS;
    }
    /** Chỉ ghi tile nền thật; OUT_OF_BOUNDS không được lưu vào map. */
    public void setTile(int x,int y,com.bombit.server.game.model.TileType type) {
        checkCoordinates(x,y); Objects.requireNonNull(type);
        if(type==com.bombit.server.game.model.TileType.OUT_OF_BOUNDS) throw new IllegalArgumentException("Not a stored tile");
        tiles[y][x]=type;
    }

    private void checkCoordinates(int tileX, int tileY) {
        if (!isInsideMap(tileX, tileY)) {
            throw new IndexOutOfBoundsException(
                    "Toa do tile nam ngoai ban do: ("
                            + tileX + ", " + tileY + ")");
        }
    }
}

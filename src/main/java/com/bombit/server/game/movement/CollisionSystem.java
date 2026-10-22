package com.bombit.server.game.movement;
import java.util.Objects;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.Player;

/**
 * Kiểm tra va chạm hitbox hình chữ nhật với map nền và biên bản đồ.
 * Chia quãng đường thành các bước tối đa một pixel để hạn chế xuyên vật cản.
 * Xử lý trục X trước Y; hiện chưa kiểm tra player-player hoặc vật cản bomb.
 * Cạnh phải/dưới không thuộc hitbox, nên đứng sát mép tường vẫn hợp lệ.
 */
public class CollisionSystem {
    // Ảnh bom của tick hiện tại; MovementSystem cập nhật từ world trước khi mô phỏng.
    private java.util.List<EntityData> entities = java.util.List.of();
    public void setEntities(java.util.List<EntityData> entities) { this.entities = java.util.List.copyOf(entities); }

    //Một bước di chuyển = 1 (pixel)
    private static final double MAX_STEP_PIXELS = 1.0;

    //Kiểm tra hitbox Player có nằm ở vị trí hợp lệ hay ko
    // Mục đích: Kiểm tra toàn bộ các tile mà hitbox phủ lên. nextDown loại cạnh phải/dưới khỏi vùng bao gồm, tránh tính nhầm tile kề khi đứng sát mép.
    public boolean canOccupy(
            GameMap map, Player player, double x, double y) {

        Objects.requireNonNull(map, "map khong duoc null");
        Objects.requireNonNull(player, "player khong duoc null");

        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0) {
            return false;
        }

        double right = x + player.getWidth();
        double bottom = y + player.getHeight();

        //So sánh hitbox người chơi với giơi hạn bản đồ
        if (!Double.isFinite(right) || !Double.isFinite(bottom)
                || right > map.getPixelWidth()
                || bottom > map.getPixelHeight()) {
            return false;
        }

        int tileSize = map.getTileSize();

        //Xác định ô hiện tại của Player
        int minTileX = (int) Math.floor(x / tileSize);
        int minTileY = (int) Math.floor(y / tileSize);

        //Cạnh phải và cạnh dưới được xem là biên ko bao gồm
        int maxTileX = (int) Math.floor(
                Math.nextDown(right) / tileSize);
        int maxTileY = (int) Math.floor(
                Math.nextDown(bottom) / tileSize);

        for (int tileY = minTileY;
             tileY <= maxTileY; tileY++) {

            for (int tileX = minTileX;
                 tileX <= maxTileX; tileX++) {

                if (!map.isWalkable(tileX, tileY)) {
                    return false;
                }
            }
        }

        // Bom chặn đường. Nhân vật đang chồng ô bom được bước ra; khi đã ra hết
        // hitbox thì không vào lại được. Quy tắc này cũng xử lý lúc hai người đứng cùng ô đặt bom.
        for (EntityData entity : entities) {
            if (entity.kind() != EntityData.Kind.BOMB) continue;
            double bx = entity.tileX() * (double) tileSize, by = entity.tileY() * (double) tileSize;
            boolean nextOverlap = x < bx + tileSize && right > bx && y < by + tileSize && bottom > by;
            boolean currentOverlap = player.getX() < bx + tileSize && player.getX() + player.getWidth() > bx
                    && player.getY() < by + tileSize && player.getY() + player.getHeight() > by;
            if (nextOverlap && !currentOverlap) return false;
        }
        return true;
    }

    //Di chuyển theo cột (x) trước, hàng (y) sau 
    // Mục đích: Dịch chuyển lần lượt từng trục thông qua các bước nhỏ; một trục bị chặn không ngăn xử lý trục còn lại.
    public void move(
            Player player, GameMap map,
            double dx, double dy) {

        Objects.requireNonNull(player, "player khong duoc null");
        Objects.requireNonNull(map, "map khong duoc null");

        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            throw new IllegalArgumentException(
                    "Quang duong di chuyen phai la so huu han");
        }

        if (!player.isAlive()) {
            return;
        }

        //Di chuyển dọc theo trục ngang
        moveAxis(player, map, dx, true);

        //Di chuyển dọc theo trục đứng
        moveAxis(player, map, dy, false);
    }

    // Mục đích: Giới hạn mỗi bước một pixel, dừng ngay trước vị trí không hợp lệ. Ngưỡng 1e-9 tránh vòng lặp do phần dư sai số double rất nhỏ.
    private void moveAxis(
            Player player, GameMap map,
            double distance, boolean horizontal) {

        double remaining = distance;

        while (Math.abs(remaining) > 1e-9) {

            double step = Math.copySign(
                    Math.min(MAX_STEP_PIXELS, Math.abs(remaining)),
                    remaining
            );

            double nextX = player.getX();
            double nextY = player.getY();

            if (horizontal) {
                nextX += step;
            } else {
                nextY += step;
            }

            //Nếu vị trí tiếp theo ko hợp lệ, dừng trục này
            if (!canOccupy(map, player, nextX, nextY)) {
                break;
            }

            player.setPosition(nextX, nextY);
            remaining -= step;
        }
    }
}

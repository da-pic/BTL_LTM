package com.bombit.server.game.core;

import java.util.*;

/**
 * Trạng thái nhân vật trên server: vị trí góc trên trái và hitbox tính bằng pixel,
 * tốc độ tính bằng pixel/giây. Hướng và moving là ý định điều khiển, chưa phải phép dịch chuyển.
 * MovementSystem và CollisionSystem quyết định vị trí thật. Player là đối tượng mutable;
 * không dùng chung trực tiếp cho network thread hoặc Swing khi GameLoop đang chạy.
 */
public class Player {
    /** Chỉ phần mô phỏng được rewind; ACK/input sequence và identity giữ giá trị hiện hành. */
    public record SimulationCheckpoint(int playerId, double x, double y, double speed,
            com.bombit.server.game.model.Direction direction, boolean moving, boolean alive,
            int bombLimit, int bombRange, int lives, int respawns, boolean shield) {
        public SimulationCheckpoint {
            validatePosition(x, y); validatePositive(speed, "speed"); Objects.requireNonNull(direction);
            if (playerId <= 0 || bombLimit <= 0 || bombRange <= 0 || lives < 0 || respawns < 0
                    || (alive && lives == 0) || (moving && !alive))
                throw new IllegalArgumentException("Invalid player checkpoint");
        }
    }

    public SimulationCheckpoint captureSimulation() {
        return new SimulationCheckpoint(id, x, y, speed, direction, moving, alive,
                bombLimit, bombRange, lives, respawns, shield);
    }

    /** Coordinator phải áp lại frozen theo kết nối hiện tại sau khi restore toàn world. */
    public void restoreSimulation(SimulationCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint);
        if (checkpoint.playerId() != id) throw new IllegalArgumentException("Wrong player checkpoint");
        x = checkpoint.x(); y = checkpoint.y(); speed = checkpoint.speed(); direction = checkpoint.direction();
        moving = checkpoint.moving(); alive = checkpoint.alive(); bombLimit = checkpoint.bombLimit();
        bombRange = checkpoint.bombRange(); lives = checkpoint.lives(); respawns = checkpoint.respawns();
        shield = checkpoint.shield();
        // Không replay mẫu input của nhánh cũ. lastProcessedInput vẫn tăng đơn điệu.
        inputAppliedThisTick = false;
    }
    private int bombLimit=1,bombRange=1;
    private int lives=1,respawns;
    private boolean shield;
    private final double spawnX,spawnY;
    public int getLives(){return lives;}
    public int getRespawns(){return respawns;}
    public boolean hasShield(){return shield;}
    public void setLives(int value){if(value<0)throw new IllegalArgumentException("Invalid lives");lives=value;setAlive(value>0);}
    public void setShield(boolean value){shield=value;}
    /** One authoritative hit. Upgrades survive respawn; eliminated players cannot revive via items. */
    public void takeDamage(){
        if(!alive)return;
        if(shield){shield=false;return;}
        lives--;setMoving(false);
        if(lives==0)setAlive(false);
        else {setPosition(spawnX,spawnY);respawns++;}
    }
    public int getBombLimit(){return bombLimit;}
    public int getBombRange(){return bombRange;}
    public void setBombLimit(int value){if(value<=0)throw new IllegalArgumentException("Invalid quota");bombLimit=value;}
    public void setBombRange(int value){if(value<=0)throw new IllegalArgumentException("Invalid range");bombRange=value;}
    /** Tâm hitbox đổi sang tọa độ ô dùng chung; không làm tròn góc trái gây lệch ô đặt bom. */
    public com.bombit.server.game.model.TilePosition tilePosition(GameMap map) {
        Objects.requireNonNull(map);
        return com.bombit.server.game.model.TilePosition.containing(x+width/2,y+height/2,map.getTileSize());
    }

    // ACK chỉ tiến sau khi server thực sự mô phỏng mẫu input; dùng để client bỏ lịch sử cũ.
    private long lastProcessedInput;
    private boolean sampledInput;
    private boolean inputAppliedThisTick;
    public long getLastProcessedInput() { return lastProcessedInput; }
    public boolean isSampledInput() { return sampledInput; }
    public void beginTick() { inputAppliedThisTick = false; }
    public boolean canAdvance() { return !sampledInput || inputAppliedThisTick; }
    public void acknowledgeInput(long sequence) {
        sampledInput = true; inputAppliedThisTick = true; lastProcessedInput = sequence;
    }

    //Hướng di chuyển 
    @Deprecated
    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }

    //ID (unique)
    private final int id;

    //Toạ độ góc trên bên trái (pixel)
    private double x;
    private double y;

    //Hitbox (pixel) (HCN tính bằng cạnh trái và dưới từ toạ độ trái)
    private final double width;
    private final double height;

    //Tốc độ di chuyển (pixel/giây)
    private double speed;

    //Trạng thái điều khiển
    private com.bombit.server.game.model.Direction direction;
    private boolean moving;
    private boolean alive;

    // Mục đích: Khởi tạo Player và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    public Player(int id, double x, double y,
                  double width, double height, double speed) {

        if (id <= 0) {
            throw new IllegalArgumentException("ID phai lon hon 0");
        }

        validatePosition(x, y);
        validatePositive(width, "width");
        validatePositive(height, "height");
        validatePositive(speed, "speed");

        this.id = id;
        this.spawnX=x;this.spawnY=y;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.speed = speed;

        this.direction = com.bombit.server.game.model.Direction.DOWN;
        this.moving = false;
        this.alive = true;
    }

    // Mục đích: Loại NaN, vô cực và vị trí âm trước khi lưu. Chưa kiểm tra tường/biên map vì Player không sở hữu map.
    private static void validatePosition(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0) {
            throw new IllegalArgumentException(
                    "Vi tri ko hop le");
        }
    }

    // Mục đích: Bảo đảm kích thước/tốc độ hữu hạn và dương để phép tính movement, chia tile và hitbox có ý nghĩa.
    private static void validatePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                    name + " Ko hop le");
        }
    }

    // Mục đích: Đọc Id để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getId() {
        return id;
    }

    // Mục đích: Đọc X để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public double getX() {
        return x;
    }

    // Mục đích: Đọc Y để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public double getY() {
        return y;
    }

    // Mục đích: Đọc Width để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public double getWidth() {
        return width;
    }

    // Mục đích: Đọc Height để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public double getHeight() {
        return height;
    }

    // Mục đích: Đọc Speed để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public double getSpeed() {
        return speed;
    }

    // Mục đích: Đọc Direction để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    @Deprecated public Direction getDirection() { return Direction.valueOf(direction.name()); }
    public com.bombit.server.game.model.Direction direction() { return direction; }
    public void setDirection(com.bombit.server.game.model.Direction direction) {
        this.direction = Objects.requireNonNull(direction);
    }

    // Mục đích: Đọc Moving để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isMoving() {
        return moving;
    }

    // Mục đích: Đọc Alive để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isAlive() {
        return alive;
    }

    // Mục đích: Ghi vị trí đã kiểm tra số học; caller vẫn phải dùng CollisionSystem để bảo đảm không xuyên map.
    public void setPosition(double x, double y) {
        validatePosition(x, y);
        this.x = x;
        this.y = y;
    }

    // Mục đích: Đổi hướng nhìn/di chuyển; không cho null để switch hướng trong MovementSystem luôn xác định.
    @Deprecated
    public void setDirection(Direction direction) {
        this.direction = com.bombit.server.game.model.Direction.valueOf(Objects.requireNonNull(direction).name());
    }

    // Mục đích: Lưu ý định đi hoặc dừng; nhân vật đã chết không thể bật moving=true.
    public void setMoving(boolean moving) {
        this.moving = moving && alive;
    }

    // Mục đích: Thay tốc độ theo đơn vị pixel/giây, ví dụ sau item Speed+; cần thực hiện từ gameplay server.
    public void setSpeed(double speed) {
        validatePositive(speed, "speed");
        this.speed = speed;
    }

    // Mục đích: Đổi trạng thái sống và dừng chuyển động ngay khi chết để tick sau không tiếp tục dịch chuyển.
    public void setAlive(boolean alive) {
        this.alive = alive;

        if (!alive) {
            this.moving = false;
        }
    }

    @Override
    // Mục đích: Tạo chuỗi chẩn đoán cho console/debug; không dùng chuỗi này làm payload protocol.
    public String toString() {
        return "Player{" +
                "id=" + id +
                ", x=" + x +
                ", y=" + y +
                ", direction=" + direction +
                ", speed=" + speed +
                ", moving=" + moving +
                ", alive=" + alive +
                '}';
    }
}

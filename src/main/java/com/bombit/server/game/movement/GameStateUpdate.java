package com.bombit.server.game.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

/**
 * Ảnh dữ liệu đồng bộ được tạo trên GameLoop rồi chuyển sang worker gửi mạng.
 * FULL_STATE chứa toàn bộ map/player/entity; DELTA chỉ chứa phần thay đổi và khóa bị xóa.
 * tick là phiên bản mới, baseTick là phiên bản client phải đang có để áp dụng delta.
 * Các collection được sao chép để thay đổi GameState sau đó không làm đổi nội dung update.
 */
public final class GameStateUpdate {
    private com.bombit.common.dto.MatchFrame matchFrame;
    public com.bombit.common.dto.MatchFrame getMatchFrame(){return matchFrame;}
    public GameStateUpdate withMatchFrame(com.bombit.common.dto.MatchFrame frame){
        var copy=withEntities(entityChanges,removedEntityKeys);copy.matchFrame=frame;return copy;
    }
    private List<com.bombit.common.dto.GameNotice> gameNotices=List.of();
    public List<com.bombit.common.dto.GameNotice> getGameNotices() { return gameNotices; }
    /** Internal handoff only; wire snapshots carry state, notices use the official event packets. */
    public GameStateUpdate withGameNotices(List<com.bombit.common.dto.GameNotice> notices) {
        var copy=withEntities(entityChanges,removedEntityKeys);copy.gameNotices=List.copyOf(notices);return copy;
    }
    private com.bombit.common.dto.BombFrame bombFrame;
    public com.bombit.common.dto.BombFrame getBombFrame() { return bombFrame; }
    /** Copies the update; the published instance is never mutated. Null retains snapshot-v2. */
    public GameStateUpdate withBombFrame(com.bombit.common.dto.BombFrame frame) {
        var copy = withEntities(entityChanges, removedEntityKeys);
        copy.bombFrame = frame;
        return copy;
    }

    public enum Type {
        FULL_STATE,
        DELTA
    }

    private final List<EntityData> entityChanges;
    private final List<String> removedEntityKeys;

    // Full chứa toàn bộ entity; delta chỉ chứa entity thêm mới hoặc thay đổi.
    // Mục đích: Đọc EntityChanges để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<EntityData> getEntityChanges() { return entityChanges; }
    // Mục đích: Đọc RemovedEntityKeys để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<String> getRemovedEntityKeys() { return removedEntityKeys; }

    // Mục đích: Tạo update mới giữ metadata/map/player và sao chép danh sách entity. Với full là toàn bộ entity, với delta là entity thêm hoặc thay đổi.
    public GameStateUpdate withEntities(List<EntityData> entities, List<String> removed) {
        var copy = new GameStateUpdate(type, tick, baseTick, mapWidth, mapHeight, tileSize,
                matchState, matchStateChanged, mapTiles(), tileChanges, players,
                playerChanges, removedPlayerIds, entities, removed);
        copy.bombFrame = bombFrame;
        copy.matchFrame = matchFrame;
        copy.gameNotices = gameNotices;
        return copy;
    }

    private final Type type;
    private final long tick;
    private final long baseTick;

    private final int mapWidth;
    private final int mapHeight;
    private final int tileSize;

    private final GameState.MatchState matchState;
    private final boolean matchStateChanged;

    private final List<com.bombit.server.game.model.TileType> fullMap;
    private final List<TileChange> tileChanges;

    private final List<PlayerData> players;
    private final List<PlayerData> playerChanges;
    private final List<Integer> removedPlayerIds;

    // Mục đích: Khởi tạo GameStateUpdate và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    private GameStateUpdate(
            Type type,
            long tick,
            long baseTick,
            int mapWidth,
            int mapHeight,
            int tileSize,
            GameState.MatchState matchState,
            boolean matchStateChanged,
            List<com.bombit.server.game.model.TileType> fullMap,
            List<TileChange> tileChanges,
            List<PlayerData> players,
            List<PlayerData> playerChanges,
            List<Integer> removedPlayerIds) {
        this(type, tick, baseTick, mapWidth, mapHeight, tileSize, matchState,
                matchStateChanged, fullMap, tileChanges, players, playerChanges,
                removedPlayerIds, List.of(), List.of());
    }

    // Mục đích: Khởi tạo GameStateUpdate và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    private GameStateUpdate(Type type, long tick, long baseTick, int mapWidth,
            int mapHeight, int tileSize, GameState.MatchState matchState,
            boolean matchStateChanged, List<com.bombit.server.game.model.TileType> fullMap,
            List<TileChange> tileChanges, List<PlayerData> players,
            List<PlayerData> playerChanges, List<Integer> removedPlayerIds,
            List<EntityData> entities, List<String> removed) {
        this.entityChanges = List.copyOf(entities);
        this.removedEntityKeys = List.copyOf(removed);
        this.type = Objects.requireNonNull(type);
        this.tick = tick;
        this.baseTick = baseTick;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.tileSize = tileSize;
        this.matchState = Objects.requireNonNull(matchState);
        this.matchStateChanged = matchStateChanged;

        this.fullMap = List.copyOf(fullMap);
        if (this.fullMap.contains(com.bombit.server.game.model.TileType.OUT_OF_BOUNDS)) {
            throw new IllegalArgumentException("Not a stored tile");
        }
        this.tileChanges = immutableCopy(tileChanges);
        this.players = immutableCopy(players);
        this.playerChanges = immutableCopy(playerChanges);
        this.removedPlayerIds = immutableCopy(removedPlayerIds);
    }

    /** Compatibility bridge; new code uses fullStateModel with the shared TileType. */
    @Deprecated
    public static GameStateUpdate fullState(long tick, int mapWidth, int mapHeight,
            int tileSize, GameState.MatchState matchState, List<GameMap.TileType> fullMap,
            List<PlayerData> players) {
        return fullStateModel(tick, mapWidth, mapHeight, tileSize, matchState,
                fullMap.stream().map(t -> com.bombit.server.game.model.TileType.valueOf(t.name())).toList(), players);
    }

    public static GameStateUpdate fullStateModel(
            long tick,
            int mapWidth,
            int mapHeight,
            int tileSize,
            GameState.MatchState matchState,
            List<com.bombit.server.game.model.TileType> fullMap,
            List<PlayerData> players) {

        return new GameStateUpdate(
                Type.FULL_STATE,
                tick,
                -1,
                mapWidth,
                mapHeight,
                tileSize,
                matchState,
                false,
                fullMap,
                Collections.emptyList(),
                players,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public static GameStateUpdate delta(
            long tick,
            long baseTick,
            int mapWidth,
            int mapHeight,
            int tileSize,
            GameState.MatchState matchState,
            boolean matchStateChanged,
            List<TileChange> tileChanges,
            List<PlayerData> playerChanges,
            List<Integer> removedPlayerIds) {

        return new GameStateUpdate(
                Type.DELTA,
                tick,
                baseTick,
                mapWidth,
                mapHeight,
                tileSize,
                matchState,
                matchStateChanged,
                Collections.emptyList(),
                tileChanges,
                Collections.emptyList(),
                playerChanges,
                removedPlayerIds
        );
    }

    private static <T> List<T> immutableCopy(List<T> source) {
        return Collections.unmodifiableList(
                new ArrayList<>(source));
    }

    // Mục đích: Đọc Type để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public Type getType() {
        return type;
    }

    // Mục đích: Đọc FullState để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isFullState() {
        return type == Type.FULL_STATE;
    }

    // Mục đích: Đọc Tick để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public long getTick() {
        return tick;
    }

    // Mục đích: Đọc BaseTick để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public long getBaseTick() {
        return baseTick;
    }

    // Mục đích: Đọc MapWidth để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getMapWidth() {
        return mapWidth;
    }

    // Mục đích: Đọc MapHeight để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getMapHeight() {
        return mapHeight;
    }

    // Mục đích: Đọc TileSize để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getTileSize() {
        return tileSize;
    }

    // Mục đích: Đọc MatchState để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public GameState.MatchState getMatchState() {
        return matchState;
    }

    // Mục đích: Đọc MatchStateChanged để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public boolean isMatchStateChanged() {
        return matchStateChanged;
    }

    //FULL_STATE: Bản đồ đc lm phẳng theo từng hàng
    //Vị trí phần tử = y * mapWidth + x.
    // Mục đích: Đọc FullMap để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<com.bombit.server.game.model.TileType> mapTiles() { return fullMap; }
    @Deprecated public List<GameMap.TileType> getFullMap() {
        return fullMap.stream().map(t -> GameMap.TileType.valueOf(t.name())).toList();
    }

    //DELTA: Các ô bản đồ có thay đổi
    // Mục đích: Đọc TileChanges để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<TileChange> getTileChanges() {
        return tileChanges;
    }

    //FULL_STATE: Danh sách tất cả Player.
    // Mục đích: Đọc Players để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<PlayerData> getPlayers() {
        return players;
    }

    //DELTA: Player mới hoặc Player có trạng thái thay đổi
    // Mục đích: Đọc PlayerChanges để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<PlayerData> getPlayerChanges() {
        return playerChanges;
    }

    //ID của các Player đã bị xoá khỏi GameState
    // Mục đích: Đọc RemovedPlayerIds để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<Integer> getRemovedPlayerIds() {
        return removedPlayerIds;
    }

    // Mục đích: Full luôn có ý nghĩa đồng bộ; delta có ý nghĩa nếu bất kỳ tile, player, entity, khóa xóa hoặc trạng thái trận thay đổi.
    public boolean hasChanges() {
        return isFullState()
                || !tileChanges.isEmpty()
                || !playerChanges.isEmpty()
                || !removedPlayerIds.isEmpty()
                || !entityChanges.isEmpty()
                || !removedEntityKeys.isEmpty()
                || bombFrame != null
                || matchFrame != null
                || !gameNotices.isEmpty()
                || matchStateChanged;
    }

    @Override
    // Mục đích: Tạo chuỗi chẩn đoán cho console/debug; không dùng chuỗi này làm payload protocol.
    public String toString() {
        return "GameStateUpdate{" +
                "type=" + type +
                ", tick=" + tick +
                ", baseTick=" + baseTick +
                ", tileChanges=" + tileChanges.size() +
                ", playerChanges=" + playerChanges.size() +
                ", removedPlayers=" + removedPlayerIds.size() +
                '}';
    }

    public static final class TileChange {

        private final int x;
        private final int y;
        private final com.bombit.server.game.model.TileType newType;

        @Deprecated
        public TileChange(
                int x, int y, GameMap.TileType newType) {
            this.x = x;
            this.y = y;
            this.newType = com.bombit.server.game.model.TileType.valueOf(Objects.requireNonNull(newType).name());
        }

        // Mục đích: Đọc X để quan sát trạng thái; phương thức không cập nhật mô phỏng.
        public int getX() {
            return x;
        }

        // Mục đích: Đọc Y để quan sát trạng thái; phương thức không cập nhật mô phỏng.
        public int getY() {
            return y;
        }

        // Mục đích: Đọc NewType để quan sát trạng thái; phương thức không cập nhật mô phỏng.
        @Deprecated public GameMap.TileType getNewType() { return GameMap.TileType.valueOf(newType.name()); }
        public com.bombit.server.game.model.TileType tileType() { return newType; }
        public TileChange(int x,int y,com.bombit.server.game.model.TileType type) {
            if(type==com.bombit.server.game.model.TileType.OUT_OF_BOUNDS)throw new IllegalArgumentException("Not a stored tile");
            this.x=x;this.y=y;this.newType=Objects.requireNonNull(type);
        }
    }

    public static final class PlayerData {

        private final long lastProcessedInput;
        public long getLastProcessedInput() { return lastProcessedInput; }
        @Deprecated
        public PlayerData(int id, double x, double y, double width, double height,
                double speed, Player.Direction direction, boolean moving, boolean alive, long ack) {
            this(id, x, y, width, height, speed,
                    com.bombit.server.game.model.Direction.valueOf(Objects.requireNonNull(direction).name()),
                    moving, alive, ack);
        }
        private final int id;
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final double speed;

        private final com.bombit.server.game.model.Direction direction;
        private final boolean moving;
        private final boolean alive;

        @Deprecated
        public PlayerData(int id, double x, double y, double width, double height,
                double speed, Player.Direction direction, boolean moving, boolean alive) {
            this(id, x, y, width, height, speed,
                    com.bombit.server.game.model.Direction.valueOf(Objects.requireNonNull(direction).name()),
                    moving, alive, 0);
        }

        public PlayerData(int id, double x, double y, double width, double height,
                double speed, com.bombit.server.game.model.Direction direction, boolean moving, boolean alive) {
            this(id, x, y, width, height, speed, direction, moving, alive, 0);
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
        @Deprecated public Player.Direction getDirection() { return Player.Direction.valueOf(direction.name()); }
        public com.bombit.server.game.model.Direction direction() { return direction; }
        public PlayerData(int id,double x,double y,double width,double height,double speed,
                com.bombit.server.game.model.Direction direction,boolean moving,boolean alive,long ack) {
            if (ack < 0) throw new IllegalArgumentException("Negative input ACK");
            this.id=id; this.x=x; this.y=y; this.width=width; this.height=height;
            this.speed=speed; this.direction=Objects.requireNonNull(direction);
            this.moving=moving; this.alive=alive; this.lastProcessedInput=ack;
        }

        // Mục đích: Đọc Moving để quan sát trạng thái; phương thức không cập nhật mô phỏng.
        public boolean isMoving() {
            return moving;
        }

        // Mục đích: Đọc Alive để quan sát trạng thái; phương thức không cập nhật mô phỏng.
        public boolean isAlive() {
            return alive;
        }
    }
}

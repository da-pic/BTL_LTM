package com.bombit.server.game.movement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

/**
 * Tạo State Delta Compression bằng cách so sánh ảnh hiện tại với baseline đã commit.
 * Baseline không nhất thiết là tick liền trước: tick rỗng hoặc enqueue thất bại không commit.
 * buildUpdate tạo bản chờ; commit chỉ được gọi sau khi enqueue đúng update đó thành công.
 * Một builder phục vụ một luồng update của một trận; không chia sẻ giữa các phòng.
 * Commit chỉ xác nhận vào queue, chưa xác nhận client đã nhận qua TCP.
 */
public class StateDeltaBuilder {
    private com.bombit.common.dto.BombFrame publishedBombFrame;
    private com.bombit.common.dto.MatchFrame publishedMatchFrame;
    private Map<String, EntityData> publishedEntities = Map.of();
    private Map<String, EntityData> pendingEntities = Map.of();

    //Trạng thái đã đc vào outbound queue thành công
    private Frame publishedFrame;
    private long publishedTick = -1;

    //Trạng thái đang chờ xác nhận
    private Frame pendingFrame;
    private GameStateUpdate pendingUpdate;

    // Mục đích: Chụp ảnh dữ liệu trước khi so sánh; trả null khi không có thay đổi. Không sửa baseline cho đến khi commit nên có thể thử lại sau lỗi enqueue.
    public GameStateUpdate buildUpdate(
            GameState state,
            long tick,
            boolean forceFullState) {

        Objects.requireNonNull(state, "state khong duoc null");

        if (tick < 0) {
            throw new IllegalArgumentException(
                    "tick khong duoc am");
        }

        Frame current = capture(state);
        // So sánh entity theo record.equals: thay đổi bất kỳ trường nào đều tạo một upsert.
        // EntityData bất biến nên baseline không bị đổi ngầm khi gameplay cập nhật ảnh mới.
        pendingEntities = new LinkedHashMap<>();
        for (EntityData entity : state.getEntities()) pendingEntities.put(entity.key(), entity);
        List<EntityData> changedEntities = new ArrayList<>();
        for (EntityData entity : pendingEntities.values()) {
            if (!entity.equals(publishedEntities.get(entity.key()))) changedEntities.add(entity);
        }
        // Entity biến mất khỏi trạng thái hiện tại phải có tombstone (khóa xóa) cho client.
        List<String> removedEntities = new ArrayList<>();
        for (String key : publishedEntities.keySet()) {
            if (!pendingEntities.containsKey(key)) removedEntities.add(key);
        }

        //Bỏ Pending cũ nếu Update trc chưa đc chấp nhận
        pendingFrame = null;
        pendingUpdate = null;

        //Gửi full state nếu là lần đầu, được yêu cầu đồng bộ lại
        //Hoặc kích thước bản đồ thay đôi
        if (forceFullState
                || publishedFrame == null
                || !sameMapDimensions(publishedFrame, current)) {

            pendingFrame = current;
            pendingUpdate = createFullState(current, tick).withEntities(
                    new ArrayList<>(pendingEntities.values()), List.of()).withBombFrame(state.getBombFrame()).withGameNotices(state.getGameNotices()).withMatchFrame(state.getMatchFrame());

            return pendingUpdate;
        }

        List<GameStateUpdate.TileChange> tileChanges =
                new ArrayList<>();

        //So dánh các ô bản đồ
        for (int y = 0; y < current.height; y++) {
            for (int x = 0; x < current.width; x++) {

                if (current.tiles[y][x]
                        != publishedFrame.tiles[y][x]) {

                    tileChanges.add(
                            new GameStateUpdate.TileChange(
                                    x, y, current.tiles[y][x]));
                }
            }
        }

        List<GameStateUpdate.PlayerData> playerChanges =
                new ArrayList<>();

        //Tìm Player mới hoặc Player có trạng thái khác
        for (Map.Entry<Integer, GameStateUpdate.PlayerData> entry
                : current.players.entrySet()) {

            GameStateUpdate.PlayerData oldPlayer =
                    publishedFrame.players.get(entry.getKey());

            if (!samePlayer(oldPlayer, entry.getValue())) {
                playerChanges.add(entry.getValue());
            }
        }

        List<Integer> removedPlayerIds = new ArrayList<>();

        //Tìm Player đã bị xoá
        for (Integer oldId : publishedFrame.players.keySet()) {
            if (!current.players.containsKey(oldId)) {
                removedPlayerIds.add(oldId);
            }
        }

        boolean matchStateChanged =
                current.matchState != publishedFrame.matchState;

        //Ko gửi packet nếu ko có thay đổi
        if (tileChanges.isEmpty()
                && playerChanges.isEmpty()
                && removedPlayerIds.isEmpty()
                && changedEntities.isEmpty()
                && removedEntities.isEmpty()
                && state.getGameNotices().isEmpty()
                && Objects.equals(publishedMatchFrame,state.getMatchFrame())
                && Objects.equals(publishedBombFrame == null ? null : publishedBombFrame.explosions(),
                                  state.getBombFrame() == null ? null : state.getBombFrame().explosions())
                && !matchStateChanged) {
            return null;
        }

        pendingFrame = current;

        pendingUpdate = GameStateUpdate.delta(
                tick,
                publishedTick,
                current.width,
                current.height,
                current.tileSize,
                current.matchState,
                matchStateChanged,
                tileChanges,
                playerChanges,
                removedPlayerIds
        ).withEntities(changedEntities, removedEntities).withBombFrame(state.getBombFrame()).withGameNotices(state.getGameNotices()).withMatchFrame(state.getMatchFrame());

        return pendingUpdate;
    }

    //Chỉ gọi sau khi outboundQueue.offer(update) thành công
    // Mục đích: Xác nhận đúng đối tượng pendingUpdate vừa tạo, lưu cả frame và entity làm baseline mới. Không dùng một update cũ để commit sau lần build khác.
    public void commit(GameStateUpdate update) {

        if (update == null
                || update != pendingUpdate
                || pendingFrame == null) {

            throw new IllegalArgumentException(
                    "Chi commit update vua duoc tao va da vao queue");
        }

        // Baseline entity và frame phải tiến cùng nhau để dùng chung một baseTick.
        publishedEntities = Map.copyOf(pendingEntities);
        publishedBombFrame = update.getBombFrame();
        publishedMatchFrame = update.getMatchFrame();
        publishedFrame = pendingFrame;
        publishedTick = update.getTick();

        pendingFrame = null;
        pendingUpdate = null;
    }

    // Mục đích: Làm phẳng map theo từng hàng: chỉ số = y * width + x, rồi đưa toàn bộ player vào full snapshot.
    private GameStateUpdate createFullState(
            Frame frame, long tick) {

        List<com.bombit.server.game.model.TileType> flatMap =
                new ArrayList<>(frame.width * frame.height);

        //Làm phẳng bản đồ theo từng hàng, hàng (y) trc, cột (x) sau
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                flatMap.add(frame.tiles[y][x]);
            }
        }

        return GameStateUpdate.fullStateModel(
                tick,
                frame.width,
                frame.height,
                frame.tileSize,
                frame.matchState,
                flatMap,
                new ArrayList<>(frame.players.values())
        );
    }

    // Mục đích: Sao chép map và từng trường Player vào ảnh riêng; không giữ tham chiếu Player mutable. Việc chụp ảnh phải chạy cùng luồng sở hữu GameState.
    private Frame capture(GameState state) {

        GameMap map = state.getMap();

        int width = map.getWidthInTiles();
        int height = map.getHeightInTiles();

        com.bombit.server.game.model.TileType[][] tiles =
                new com.bombit.server.game.model.TileType[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = map.tileAt(x, y);
            }
        }

        Map<Integer, GameStateUpdate.PlayerData> players =
                new LinkedHashMap<>();

        for (Player player : state.getPlayers()) {

            GameStateUpdate.PlayerData data =
                    new GameStateUpdate.PlayerData(
                            player.getId(),
                            player.getX(),
                            player.getY(),
                            player.getWidth(),
                            player.getHeight(),
                            player.getSpeed(),
                            player.direction(),
                            player.isMoving(),
                            player.isAlive(),
                            player.getLastProcessedInput()
                    );

            players.put(data.getId(), data);
        }

        return new Frame(
                width,
                height,
                map.getTileSize(),
                state.getMatchState(),
                tiles,
                players
        );
    }

    // Mục đích: Nếu kích thước hoặc tileSize thay đổi thì delta dựa trên map cũ không còn đủ thông tin, phải gửi full.
    private boolean sameMapDimensions(Frame a, Frame b) {
        return a.width == b.width
                && a.height == b.height
                && a.tileSize == b.tileSize;
    }

    // Mục đích: So sánh toàn bộ trường được truyền, gồm vị trí, hitbox, speed, hướng và alive/moving. So sánh double chính xác; chưa lượng tử hóa tọa độ.
    private boolean samePlayer(
            GameStateUpdate.PlayerData a,
            GameStateUpdate.PlayerData b) {

        if (a == null || b == null) {
            return false;
        }

        return a.getId() == b.getId()
                && Double.compare(a.getX(), b.getX()) == 0
                && Double.compare(a.getY(), b.getY()) == 0
                && Double.compare(a.getWidth(), b.getWidth()) == 0
                && Double.compare(a.getHeight(), b.getHeight()) == 0
                && Double.compare(a.getSpeed(), b.getSpeed()) == 0
                && a.direction() == b.direction()
                && a.isMoving() == b.isMoving()
                && a.isAlive() == b.isAlive()
                && a.getLastProcessedInput() == b.getLastProcessedInput();
    }

    private static final class Frame {

        private final int width;
        private final int height;
        private final int tileSize;

        private final GameState.MatchState matchState;
        private final com.bombit.server.game.model.TileType[][] tiles;

        private final Map<Integer, GameStateUpdate.PlayerData> players;

        private Frame(
                int width,
                int height,
                int tileSize,
                GameState.MatchState matchState,
                com.bombit.server.game.model.TileType[][] tiles,
                Map<Integer, GameStateUpdate.PlayerData> players) {

            this.width = width;
            this.height = height;
            this.tileSize = tileSize;
            this.matchState = matchState;
            this.tiles = tiles;
            this.players = players;
        }
    }
}

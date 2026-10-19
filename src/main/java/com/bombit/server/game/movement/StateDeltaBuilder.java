package com.bombit.server.game.movement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

public class StateDeltaBuilder {

    //Trạng thái đã đc vào outbound queue thành công
    private Frame publishedFrame;
    private long publishedTick = -1;

    //Trạng thái đang chờ xác nhận
    private Frame pendingFrame;
    private GameStateUpdate pendingUpdate;

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

        //Bỏ Pending cũ nếu Update trc chưa đc chấp nhận
        pendingFrame = null;
        pendingUpdate = null;

        //Gửi full state nếu là lần đầu, được yêu cầu đồng bộ lại
        //Hoặc kích thước bản đồ thay đôi
        if (forceFullState
                || publishedFrame == null
                || !sameMapDimensions(publishedFrame, current)) {

            pendingFrame = current;
            pendingUpdate = createFullState(current, tick);

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
        );

        return pendingUpdate;
    }

    //Chỉ gọi sau khi outboundQueue.offer(update) thành công
    public void commit(GameStateUpdate update) {

        if (update == null
                || update != pendingUpdate
                || pendingFrame == null) {

            throw new IllegalArgumentException(
                    "Chi commit update vua duoc tao va da vao queue");
        }

        publishedFrame = pendingFrame;
        publishedTick = update.getTick();

        pendingFrame = null;
        pendingUpdate = null;
    }

    private GameStateUpdate createFullState(
            Frame frame, long tick) {

        List<GameMap.TileType> flatMap =
                new ArrayList<>(frame.width * frame.height);

        //Làm phẳng bản đồ theo từng hàng, hàng (y) trc, cột (x) sau
        for (int y = 0; y < frame.height; y++) {
            for (int x = 0; x < frame.width; x++) {
                flatMap.add(frame.tiles[y][x]);
            }
        }

        return GameStateUpdate.fullState(
                tick,
                frame.width,
                frame.height,
                frame.tileSize,
                frame.matchState,
                flatMap,
                new ArrayList<>(frame.players.values())
        );
    }

    private Frame capture(GameState state) {

        GameMap map = state.getMap();

        int width = map.getWidthInTiles();
        int height = map.getHeightInTiles();

        GameMap.TileType[][] tiles =
                new GameMap.TileType[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                tiles[y][x] = map.getTile(x, y);
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
                            player.getDirection(),
                            player.isMoving(),
                            player.isAlive()
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

    private boolean sameMapDimensions(Frame a, Frame b) {
        return a.width == b.width
                && a.height == b.height
                && a.tileSize == b.tileSize;
    }

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
                && a.getDirection() == b.getDirection()
                && a.isMoving() == b.isMoving()
                && a.isAlive() == b.isAlive();
    }

    private static final class Frame {

        private final int width;
        private final int height;
        private final int tileSize;

        private final GameState.MatchState matchState;
        private final GameMap.TileType[][] tiles;

        private final Map<Integer, GameStateUpdate.PlayerData> players;

        private Frame(
                int width,
                int height,
                int tileSize,
                GameState.MatchState matchState,
                GameMap.TileType[][] tiles,
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
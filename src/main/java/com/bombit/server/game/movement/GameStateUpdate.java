package com.bombit.server.game.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

public final class GameStateUpdate {

    public enum Type {
        FULL_STATE,
        DELTA
    }

    private final Type type;
    private final long tick;
    private final long baseTick;

    private final int mapWidth;
    private final int mapHeight;
    private final int tileSize;

    private final GameState.MatchState matchState;
    private final boolean matchStateChanged;

    private final List<GameMap.TileType> fullMap;
    private final List<TileChange> tileChanges;

    private final List<PlayerData> players;
    private final List<PlayerData> playerChanges;
    private final List<Integer> removedPlayerIds;

    private GameStateUpdate(
            Type type,
            long tick,
            long baseTick,
            int mapWidth,
            int mapHeight,
            int tileSize,
            GameState.MatchState matchState,
            boolean matchStateChanged,
            List<GameMap.TileType> fullMap,
            List<TileChange> tileChanges,
            List<PlayerData> players,
            List<PlayerData> playerChanges,
            List<Integer> removedPlayerIds) {

        this.type = Objects.requireNonNull(type);
        this.tick = tick;
        this.baseTick = baseTick;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.tileSize = tileSize;
        this.matchState = Objects.requireNonNull(matchState);
        this.matchStateChanged = matchStateChanged;

        this.fullMap = immutableCopy(fullMap);
        this.tileChanges = immutableCopy(tileChanges);
        this.players = immutableCopy(players);
        this.playerChanges = immutableCopy(playerChanges);
        this.removedPlayerIds = immutableCopy(removedPlayerIds);
    }

    public static GameStateUpdate fullState(
            long tick,
            int mapWidth,
            int mapHeight,
            int tileSize,
            GameState.MatchState matchState,
            List<GameMap.TileType> fullMap,
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

    public Type getType() {
        return type;
    }

    public boolean isFullState() {
        return type == Type.FULL_STATE;
    }

    public long getTick() {
        return tick;
    }

    public long getBaseTick() {
        return baseTick;
    }

    public int getMapWidth() {
        return mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public int getTileSize() {
        return tileSize;
    }

    public GameState.MatchState getMatchState() {
        return matchState;
    }

    public boolean isMatchStateChanged() {
        return matchStateChanged;
    }

    //FULL_STATE: Bản đồ đc lm phẳng theo từng hàng
    //Vị trí phần tử = y * mapWidth + x.
    public List<GameMap.TileType> getFullMap() {
        return fullMap;
    }

    //DELTA: Các ô bản đồ có thay đổi
    public List<TileChange> getTileChanges() {
        return tileChanges;
    }

    //FULL_STATE: Danh sách tất cả Player.
    public List<PlayerData> getPlayers() {
        return players;
    }

    //DELTA: Player mới hoặc Player có trạng thái thay đổi
    public List<PlayerData> getPlayerChanges() {
        return playerChanges;
    }

    //ID của các Player đã bị xoá khỏi GameState
    public List<Integer> getRemovedPlayerIds() {
        return removedPlayerIds;
    }

    public boolean hasChanges() {
        return isFullState()
                || !tileChanges.isEmpty()
                || !playerChanges.isEmpty()
                || !removedPlayerIds.isEmpty()
                || matchStateChanged;
    }

    @Override
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
        private final GameMap.TileType newType;

        public TileChange(
                int x, int y, GameMap.TileType newType) {
            this.x = x;
            this.y = y;
            this.newType = Objects.requireNonNull(newType);
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public GameMap.TileType getNewType() {
            return newType;
        }
    }

    public static final class PlayerData {

        private final int id;
        private final double x;
        private final double y;
        private final double width;
        private final double height;
        private final double speed;

        private final Player.Direction direction;
        private final boolean moving;
        private final boolean alive;

        public PlayerData(
                int id,
                double x,
                double y,
                double width,
                double height,
                double speed,
                Player.Direction direction,
                boolean moving,
                boolean alive) {

            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.speed = speed;
            this.direction = Objects.requireNonNull(direction);
            this.moving = moving;
            this.alive = alive;
        }

        public int getId() {
            return id;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        public double getSpeed() {
            return speed;
        }

        public Player.Direction getDirection() {
            return direction;
        }

        public boolean isMoving() {
            return moving;
        }

        public boolean isAlive() {
            return alive;
        }
    }
}
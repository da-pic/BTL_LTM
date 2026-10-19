
package com.bombit.server.game.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GameState {

    //Trạng thái của trận 
    public enum MatchState {
        WAITING,
        RUNNING,
        FINISHED
    }

    //Bản đồ của trận
    private final GameMap gameMap;

    //Danh sách Player (Key = ID)
    private final Map<Integer, Player> players = new LinkedHashMap<>();

    //Trạng thái mặc định là đang chờ để bắt đầu trận (ƯAITING)
    private MatchState matchState = MatchState.WAITING;

    public GameState(GameMap gameMap) {
        this.gameMap = Objects.requireNonNull(
                gameMap, "GameMap khong duoc null");
    }

    public GameMap getMap() {
        return gameMap;
    }

    public MatchState getMatchState() {
        return matchState;
    }

    public int getPlayerCount() {
        return players.size();
    }

    //Thêm Player nếu ID đó chưa có trong gamestate hiện tại
    public boolean addPlayer(Player player) {
        Objects.requireNonNull(player, "Player khong duoc null");

        if (matchState != MatchState.WAITING) {
            throw new IllegalStateException(
                    "Chi duoc them Player khi tran dang cho");
        }

        int playerId = player.getId();

        if (players.containsKey(playerId)) {
            return false;
        }

        players.put(playerId, player);
        return true;
    }

    //Tìm Player theo ID
    public Player getPlayer(int playerId) {
        return players.get(playerId);
    }

    //Xoá Player theo ID
    public boolean removePlayer(int playerId) {
        return players.remove(playerId) != null;
    }

    //Trả về danh sách ko thể xoá/thêm Player trực tiếp
    public List<Player> getPlayers() {
        return Collections.unmodifiableList(
                new ArrayList<>(players.values()));
    }

    //Bắt đầu trận
    public void startMatch() {
        if (matchState != MatchState.WAITING) {
            throw new IllegalStateException(
                    "Tran dau khong o trang thai WAITING");
        }

        if (players.isEmpty()) {
            throw new IllegalStateException(
                    "Khong the bat dau khi chua co Player");
        }

        matchState = MatchState.RUNNING;
    }

    //Kết thúc trận
    public void finishMatch() {
        matchState = MatchState.FINISHED;

        for (Player player : players.values()) {
            player.setMoving(false);
        }
    }
}

package com.bombit.server.game.checkpoint;

import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TileType;
import java.util.*;

/**
 * Phần checkpoint core: map và thuộc tính player, không thay thế checkpoint bomb/item.
 * Không lưu session, frozen, ACK, entity projection, event ID hoặc kết quả đã chốt.
 * Dùng list phẳng bất biến để không chia sẻ mảng map với lịch sử.
 */
public record CoreWorldCheckpoint(int width, int height, int tileSize, List<TileType> tiles,
        List<Player.SimulationCheckpoint> players) {
    public CoreWorldCheckpoint {
        tiles = List.copyOf(tiles); players = List.copyOf(players);
        if (width <= 0 || height <= 0 || tileSize <= 0 || (long) width * height != tiles.size()
                || tiles.contains(TileType.OUT_OF_BOUNDS)
                || players.stream().map(Player.SimulationCheckpoint::playerId).distinct().count() != players.size())
            throw new IllegalArgumentException("Invalid core checkpoint");
    }

    public static CoreWorldCheckpoint capture(GameState state) {
        if (state.getMatchState() != GameState.MatchState.RUNNING) throw new IllegalStateException("Match not running");
        var map = state.getMap();
        var cells = new ArrayList<TileType>();
        for (int y = 0; y < map.getHeightInTiles(); y++)
            for (int x = 0; x < map.getWidthInTiles(); x++) cells.add(map.tileAt(x, y));
        return new CoreWorldCheckpoint(map.getWidthInTiles(), map.getHeightInTiles(), map.getTileSize(),
                cells, state.getPlayers().stream().map(Player::captureSimulation).toList());
    }

    public void validateRestore(GameState state) {
        var map = state.getMap();
        if (state.getMatchState() != GameState.MatchState.RUNNING || width != map.getWidthInTiles()
                || height != map.getHeightInTiles() || tileSize != map.getTileSize())
            throw new IllegalStateException("Wrong map or finished match");
        var known = new HashSet<Integer>();
        players.forEach(p -> known.add(p.playerId()));
        if (state.getPlayers().stream().anyMatch(p -> !known.contains(p.getId())))
            throw new IllegalArgumentException("Checkpoint does not belong to current roster");
    }

    public void restore(GameState state) {
        validateRestore(state);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) state.getMap().setTile(x, y, tiles.get(y * width + x));
        for (var checkpoint : players) {
            var player = state.getPlayer(checkpoint.playerId());
            // Identity đã bị loại do hết grace không được tạo lại từ lịch sử.
            if (player != null) player.restoreSimulation(checkpoint);
        }
    }
}

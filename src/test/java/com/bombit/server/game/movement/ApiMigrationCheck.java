package com.bombit.server.game.movement;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import java.util.ArrayList;
import java.util.List;

/** Regression checks for legacy adapters and unchanged v2 payloads. */
public final class ApiMigrationCheck {
    @SuppressWarnings("deprecation") // Deliberately exercise public compatibility bridges.
    public static void main(String[] args) throws Exception {
        for (Direction direction : Direction.values()) {
            var input = new MovePacketCodec.Input(7, direction, true);
            var legacy = new MovePacketCodec.Input(7, Player.Direction.valueOf(direction.name()), true);
            String expected = "{\"sequence\":7,\"direction\":\"" + direction + "\",\"moving\":true}";
            check(MovePacketCodec.encode(input).getJsonPayload().equals(expected), "MOVE wire format");
            check(MovePacketCodec.encode(legacy).getJsonPayload().equals(expected), "legacy MOVE bridge");
            check(MovePacketCodec.decode(MovePacketCodec.encode(input)).equals(input), "MOVE round trip");
        }
        var player = new GameStateUpdate.PlayerData(1, 0, 0, 24, 24, 100, Direction.RIGHT, true, true, 7);
        var legacyPlayer = new GameStateUpdate.PlayerData(1, 0, 0, 24, 24, 100, Player.Direction.RIGHT, true, true, 7);
        var tiles = new ArrayList<>(List.of(TileType.EMPTY, TileType.HARD_WALL, TileType.BREAKABLE_BLOCK));
        var current = GameStateUpdate.fullStateModel(1, 3, 1, 32, GameState.MatchState.RUNNING, tiles, List.of(player));
        var legacy = GameStateUpdate.fullState(1, 3, 1, 32, GameState.MatchState.RUNNING,
                List.of(GameMap.TileType.EMPTY, GameMap.TileType.HARD_WALL, GameMap.TileType.BREAKABLE_BLOCK), List.of(legacyPlayer));
        check(SnapshotPacketCodec.encode(current).getJsonPayload().equals(
                SnapshotPacketCodec.encode(legacy).getJsonPayload()), "snapshot bridge wire equivalence");
        tiles.set(0, TileType.HARD_WALL);
        check(current.mapTiles().get(0) == TileType.EMPTY, "snapshot defensive copy");
        var decoded = SnapshotPacketCodec.decode(SnapshotPacketCodec.encode(current.withEntities(List.of(), List.of())));
        check(decoded.mapTiles().equals(current.mapTiles()), "all stored tile values round trip");
        check(decoded.getPlayers().get(0).direction() == Direction.RIGHT
                && decoded.getPlayers().get(0).getLastProcessedInput() == 7, "direction and ACK preserved");
        try {
            GameStateUpdate.fullStateModel(1, 1, 1, 32, GameState.MatchState.RUNNING,
                    List.of(TileType.OUT_OF_BOUNDS), List.of());
            throw new AssertionError("sentinel accepted as stored tile");
        } catch (IllegalArgumentException expected) {
            // Sentinel is for map queries only and must never become a v2 wire tile.
        }
        System.out.println("PASS: API compatibility, MOVE/snapshot wire format, immutable map, sentinel validation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

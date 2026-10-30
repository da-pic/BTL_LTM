package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.MetadataV3Codec;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.mode.ModeConfigurationException;
import com.bombit.server.game.mode.ModeRegistry;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hình học/đường đi và dữ liệu bàn giao thật; không cần khởi động GUI hoặc gameplay chưa triển khai. */
class ShadowFoundationTest {
    private static final String ROOM = UUID.randomUUID().toString();

    private static RoomStateView room(int count, boolean ready) {
        var members = new ArrayList<RoomStateView.Member>();
        for (int slot = 1; slot <= count; slot++) members.add(new RoomStateView.Member(
                new UUID(0, slot).toString(), "Player " + slot, slot == 1 || !ready ? "IN_ROOM" : "READY",
                slot, ShadowRoster.teamForSlot(slot), slot == 1 || !ready ? null : 4L));
        return new RoomStateView(ROOM, "Shadow", members.getFirst().sessionId(), "WAITING", 4,
                MapSelection.FIRE, 4, ShadowMatchSetup.newConfiguration(), members);
    }

    @Test void arenaHasExactObstaclesSymmetryClearSpawnsAndReachableStations() {
        var map = ShadowArena.create();
        int walls = 0, blocks = 0;
        for (int y = 0; y < 15; y++) for (int x = 0; x < 17; x++) {
            var tile = map.tileAt(x, y);
            if (tile == TileType.HARD_WALL) walls++;
            if (tile == TileType.BREAKABLE_BLOCK) blocks++;
            if (x == 0 || y == 0 || x == 16 || y == 14) assertEquals(TileType.HARD_WALL, tile);
            assertEquals(tile, map.tileAt(16 - x, y));
            assertEquals(tile, map.tileAt(x, 14 - y));
        }
        assertEquals(96, walls);
        assertEquals(36, blocks);
        assertEquals(List.of(new TilePosition(8, 3), new TilePosition(8, 7), new TilePosition(8, 11)), ShadowArena.STATIONS);
        int[][] distances = {{9, 13, 17}, {9, 13, 17}, {17, 13, 9}, {17, 13, 9}};
        for (int slot = 1; slot <= 4; slot++) {
            var spawn = ShadowArena.spawn(slot);
            assertEquals(TileType.EMPTY, map.tileAt(spawn.x(), spawn.y()));
            // Có cả lối ngang và dọc để thoát bom từ góc spawn.
            assertEquals(TileType.EMPTY, map.tileAt(spawn.x() == 1 ? 2 : 14, spawn.y()));
            assertEquals(TileType.EMPTY, map.tileAt(spawn.x(), spawn.y() == 1 ? 2 : 12));
            for (int station = 0; station < 3; station++)
                assertEquals(distances[slot - 1][station], distance(map, spawn, ShadowArena.STATIONS.get(station)));
        }
    }

    private static int distance(GameMap map, TilePosition start, TilePosition target) {
        var queue = new ArrayDeque<TilePosition>();
        var distances = new HashMap<TilePosition, Integer>();
        queue.add(start); distances.put(start, 0);
        while (!queue.isEmpty()) {
            var cell = queue.remove();
            if (cell.equals(target)) return distances.get(cell);
            for (var next : List.of(new TilePosition(cell.x() + 1, cell.y()), new TilePosition(cell.x() - 1, cell.y()),
                    new TilePosition(cell.x(), cell.y() + 1), new TilePosition(cell.x(), cell.y() - 1))) {
                if (map.isInsideMap(next.x(), next.y()) && map.tileAt(next.x(), next.y()) == TileType.EMPTY
                        && !distances.containsKey(next)) {
                    distances.put(next, distances.get(cell) + 1); queue.add(next);
                }
            }
        }
        return -1;
    }

    @Test void setupProducesCenteredSpawnsFrozenTeamsFreshMapsAndValidWireMetadata() {
        var view = room(4, true);
        var setup = ShadowMatchSetup.prepare(view);
        assertSame(view.configuration(), setup.configuration());
        assertEquals(4, setup.configurationRevision());
        assertEquals(List.of(TeamId.A, TeamId.B, TeamId.A, TeamId.B), setup.roster().players().stream().map(MatchStartMetadata.Member::team).toList());
        for (int id = 1; id <= 4; id++) {
            var spawn = setup.spawns().get(id - 1);
            var cell = ShadowArena.spawn(id);
            assertEquals(id, spawn.playerId()); assertEquals(setup.roster().player(id).sessionId(), spawn.sessionId());
            assertEquals(cell.x() * 32 + 4, spawn.x()); assertEquals(cell.y() * 32 + 4, spawn.y());
            assertEquals(24, spawn.width()); assertEquals(24, spawn.height()); assertEquals(80, spawn.speed());
            var metadata = setup.metadata(UUID.randomUUID(), MapTheme.ICE, id);
            assertEquals(metadata, MetadataV3Codec.decodeStarted(MetadataV3Codec.started(metadata)));
            assertEquals(id, metadata.stream().recipientPlayerId());
        }
        var first = setup.createMap();var second = setup.createMap();
        first.setTile(4, 1, TileType.EMPTY);
        assertEquals(TileType.BREAKABLE_BLOCK, second.tileAt(4, 1));
        assertThrows(UnsupportedOperationException.class, () -> setup.spawns().clear());
        assertThrows(UnsupportedOperationException.class, () -> setup.roster().players().clear());
        assertThrows(UnsupportedOperationException.class, () -> ShadowArena.STATIONS.clear());
    }

    @Test void preparationRejectsMissingUnreadyOrPlayingPlayersAndRegistrySupportsCompleteShadow() {
        assertThrows(IllegalArgumentException.class, () -> ShadowMatchSetup.prepare(room(3, true)));
        assertThrows(IllegalStateException.class, () -> ShadowMatchSetup.prepare(room(4, false)));
        var view = room(4, true);
        var playing = new RoomStateView(view.roomId(), view.roomName(), view.hostId(), "PLAYING", 4,
                view.mapSelection(), view.configurationRevision(), view.configuration(), view.players());
        assertThrows(IllegalArgumentException.class, () -> ShadowMatchSetup.prepare(playing));
        var registry = new ModeRegistry();
        assertTrue(registry.find(GameMode.SHADOW_RELAY).orElseThrow().descriptor().available());
        assertDoesNotThrow(() -> registry.resolvePublic(GameMode.SHADOW_RELAY));
        assertDoesNotThrow(() -> registry.validatePublicStart(view.configuration(), 4));
        assertThrows(ModeConfigurationException.class, () -> registry.resolvePublic(GameMode.CIRCUIT));
        assertThrows(IllegalArgumentException.class, () -> ShadowArena.spawn(0));
        assertThrows(IllegalArgumentException.class, () -> ShadowRoster.teamForSlot(5));
    }

    @Test void rosterRejectsDuplicateSessionsAndCopiesItsInput() {
        var input = new ArrayList<>(ShadowMatchSetup.prepare(room(4, true)).roster().players());
        var roster = new ShadowRoster(input);input.clear();assertEquals(4, roster.players().size());
        var duplicate = new ArrayList<>(roster.players());var old = duplicate.get(1);
        duplicate.set(1, new MatchStartMetadata.Member(roster.player(1).sessionId(), 2, 2, old.username(), TeamId.B));
        assertThrows(IllegalArgumentException.class, () -> new ShadowRoster(duplicate));
    }
}

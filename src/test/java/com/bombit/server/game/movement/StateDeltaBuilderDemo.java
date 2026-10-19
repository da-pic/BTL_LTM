package com.bombit.server.game.movement;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.core.Player;

public class StateDeltaBuilderDemo {

    public static void main(String[] args) {

        // 1. Tao map va dat mot block co the pha.
        GameMap map = new GameMap(8, 6, 32);

        map.setTile(
                3, 2, GameMap.TileType.BREAKABLE_BLOCK);

        // 2. Tao GameState va Player.
        GameState state = new GameState(map);

        Player p1 = new Player(
                1, 32, 32, 24, 24, 150);

        state.addPlayer(p1);

        // 3. Tao bo tao delta.
        StateDeltaBuilder builder = new StateDeltaBuilder();

        System.out.println("=== TEST 1: FULL STATE BAN DAU ===");

        GameStateUpdate full =
                builder.buildUpdate(state, 1, true);

        check(full != null, "Tao duoc FULL_STATE");
        check(full.isFullState(), "Loai update la FULL_STATE");
        check(full.getTick() == 1, "Tick ban dau bang 1");
        check(full.getFullMap().size() == 48,
                "Map co du 48 tile");

        int blockIndex = 2 * map.getWidthInTiles() + 3;

        check(
                full.getFullMap().get(blockIndex)
                        == GameMap.TileType.BREAKABLE_BLOCK,
                "FULL_STATE chua block tai (3,2)");

        check(full.getPlayers().size() == 1,
                "FULL_STATE co mot Player");

        builder.commit(full);

        System.out.println("\n=== TEST 2: KHONG CO THAY DOI ===");

        GameStateUpdate unchanged =
                builder.buildUpdate(state, 2, false);

        check(
                unchanged == null,
                "Khong tao delta rong khi trang thai khong doi");

        System.out.println("\n=== TEST 3: PLAYER DI CHUYEN ===");

        p1.setPosition(64, 32);
        p1.setDirection(Player.Direction.RIGHT);
        p1.setMoving(true);

        GameStateUpdate playerDelta =
                builder.buildUpdate(state, 3, false);

        check(playerDelta != null, "Tao delta cho Player");
        check(!playerDelta.isFullState(), "Khong gui lai full state");
        check(playerDelta.getBaseTick() == 1,
                "Delta dua tren tick 1 vi tick 2 khong doi");

        check(playerDelta.getPlayerChanges().size() == 1,
                "Chi co mot Player thay doi");

        check(playerDelta.getTileChanges().isEmpty(),
                "Khong gui tile khi map khong doi");

        check(
                playerDelta.getPlayerChanges().get(0).getX() == 64,
                "Delta chua toa do X moi");

        builder.commit(playerDelta);

        System.out.println("\n=== TEST 4: PHA BLOCK ===");

        boolean destroyed = map.destroyBlock(3, 2);

        check(destroyed, "Pha block thanh cong");

        GameStateUpdate tileDelta =
                builder.buildUpdate(state, 4, false);

        check(tileDelta != null, "Tao delta cho tile");
        check(tileDelta.getBaseTick() == 3,
                "Tile delta dua tren tick 3");

        check(tileDelta.getTileChanges().size() == 1,
                "Chi gui mot tile thay doi");

        GameStateUpdate.TileChange tileChange =
                tileDelta.getTileChanges().get(0);

        check(tileChange.getX() == 3,
                "Tile thay doi dung cot 3");

        check(tileChange.getY() == 2,
                "Tile thay doi dung hang 2");

        check(tileChange.getNewType() == GameMap.TileType.EMPTY,
                "Tile moi la EMPTY");

        check(tileDelta.getPlayerChanges().isEmpty(),
                "Khong gui Player khi Player khong doi");

        builder.commit(tileDelta);

        System.out.println("\n=== TEST 5: THAY DOI MATCH STATE ===");

        state.startMatch();

        GameStateUpdate matchDelta =
                builder.buildUpdate(state, 5, false);

        check(matchDelta != null,
                "Tao delta khi MatchState thay doi");

        check(matchDelta.isMatchStateChanged(),
                "Delta danh dau MatchState thay doi");

        check(
                matchDelta.getMatchState()
                        == GameState.MatchState.RUNNING,
                "Trang thai moi la RUNNING");

        builder.commit(matchDelta);

        System.out.println("\n=== TEST 6: XOA PLAYER ===");

        state.removePlayer(1);

        GameStateUpdate removeDelta =
                builder.buildUpdate(state, 6, false);

        check(removeDelta != null,
                "Tao delta khi Player bi xoa");

        check(removeDelta.getBaseTick() == 5,
                "Remove delta dua tren tick 5");

        check(removeDelta.getRemovedPlayerIds().size() == 1,
                "Delta co mot Player bi xoa");

        check(removeDelta.getRemovedPlayerIds().contains(1),
                "ID Player bi xoa la 1");

        check(removeDelta.getPlayerChanges().isEmpty(),
                "Player bi xoa khong nam trong playerChanges");

        builder.commit(removeDelta);

        System.out.println("\n=== TEST 7: FULL STATE DONG BO LAI ===");

        GameStateUpdate resync =
                builder.buildUpdate(state, 100, true);

        check(resync != null, "Tao full state dong bo lai");
        check(resync.isFullState(), "Resync dung FULL_STATE");
        check(resync.getTick() == 100, "Tick resync bang 100");
        check(resync.getBaseTick() == -1,
                "FULL_STATE khong phu thuoc baseTick cu");

        check(resync.getFullMap().size() == 48,
                "Full map resync co du 48 tile");

        check(resync.getPlayers().isEmpty(),
                "Resync khong con Player da bi xoa");

        builder.commit(resync);

        System.out.println("\n=== KET THUC KIEM THU ===");
    }

    private static void check(
            boolean condition, String description) {

        if (!condition) {
            throw new AssertionError(
                    "FAILED: " + description);
        }

        System.out.println("[PASS] " + description);
    }
}

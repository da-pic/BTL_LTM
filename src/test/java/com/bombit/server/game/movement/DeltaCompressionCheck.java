package com.bombit.server.game.movement;

import com.bombit.server.game.model.TileType;
import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.*;

/**
 * Bộ hồi quy không phụ thuộc JUnit và phép so sánh dung lượng trong 200 tick mô phỏng.
 * Kiểm tra codec, entity thêm/xóa, player xóa, baseline, mất delta và full phục hồi.
 * So sánh hai cách gửi trên cùng world: full mỗi tick và delta có full định kỳ.
 * Đo frame ứng dụng sau PacketEncoder, gồm JSON/Base64 và header; chưa tính TCP/IP.
 * 200 tick tương ứng 10 giây mô phỏng ở 20 Hz, không phải chờ 10 giây thực.
 */
public final class DeltaCompressionCheck {
    // Mục đích: Ném AssertionError khi điều kiện sai để chạy CLI trả mã lỗi; không phụ thuộc cờ -ea của JVM.
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    // Mục đích: Đo chiều dài frame sau codec và PacketEncoder; update null tương ứng không gửi packet và được tính 0 byte.
    private static int bytes(GameStateUpdate update) throws Exception {
        return update == null ? 0 : PacketEncoder.encode(SnapshotPacketCodec.encode(update)).length;
    }
    // Mục đích: Encode rồi decode để kiểm tra dữ liệu đi qua định dạng truyền, thay vì đưa trực tiếp object server vào replica.
    private static GameStateUpdate roundTrip(GameStateUpdate update) throws Exception {
        return SnapshotPacketCodec.decode(SnapshotPacketCodec.encode(update));
    }
    // Mục đích: Chạy các bước kiểm tra của DeltaCompressionCheck; xem chú thích tại từng kịch bản và kết quả console.
    public static void main(String[] args) throws Exception {
        GameState state = new GameState(new GameMap(17, 15, 32));
        Player player = new Player(1, 32, 32, 24, 24, 150);
        state.addPlayer(player); state.startMatch();
        var builder = new StateDeltaBuilder();
        var client = new ClientWorldState();
        var full = builder.buildUpdate(state, 1, true);
        check(client.apply(roundTrip(full)) == ClientWorldState.Result.APPLIED, "initial full");
        builder.commit(full);
        check(builder.buildUpdate(state, 2, false) == null, "idle produces no delta");
        state.getMap().setTile(3, 3, TileType.BREAKABLE_BLOCK);
        state.putEntity(new EntityData(EntityData.Kind.BOMB, 1, 2, 2, "1", 2, 3000));
        var uncommitted = builder.buildUpdate(state, 3, false);
        check(uncommitted.getTileChanges().size() == 1, "one changed tile");
        var delta = builder.buildUpdate(state, 4, false);
        check(delta.getBaseTick() == 1 && delta.getEntityChanges().size() == 1, "failed enqueue retains baseline");
        client.apply(roundTrip(delta)); builder.commit(delta);
        check(client.getEntities().size() == 1, "bomb replicated");
        state.removeEntity("BOMB:1"); state.removePlayer(1);
        delta = builder.buildUpdate(state, 5, false);
        client.apply(roundTrip(delta)); builder.commit(delta);
        check(client.getEntities().isEmpty() && client.getPlayers().isEmpty(), "entity and player removal");
        state.getMap().destroyBlock(3, 3);
        delta = builder.buildUpdate(state, 6, false); builder.commit(delta); // giả lập một update đã commit nhưng client không nhận được
        state.putEntity(new EntityData(EntityData.Kind.ITEM, 1, 3, 3, "", 0, 0));
        delta = builder.buildUpdate(state, 7, false);
        check(client.apply(roundTrip(delta)) == ClientWorldState.Result.NEED_FULL_STATE, "detect missing baseline");
        check(client.getTick() == 5, "rejection is atomic");
        var recovery = builder.buildUpdate(state, 8, true);
        client.apply(roundTrip(recovery)); builder.commit(recovery);
        check(!client.needsFullState() && client.getEntities().size() == 1, "full resync");
        check(client.apply(roundTrip(full)) == ClientWorldState.Result.STALE, "ignore old full");
        try {
            SnapshotPacketCodec.decode(new Packet(PacketType.GAME_SNAPSHOT, "{}"));
            throw new AssertionError("malformed envelope accepted");
        } catch (java.io.IOException expected) { }

        // So sánh cùng world ở mỗi tick; tính cả full định kỳ tại tick 100 và 200.
        state = new GameState(new GameMap(17, 15, 32));
        player = new Player(1, 32, 32, 24, 24, 150);
        state.addPlayer(player); state.startMatch();
        builder = new StateDeltaBuilder(); client = new ClientWorldState();
        long fullBytes = 0, deltaBytes = 0;
        int packets = 0;
        System.out.println("tick,full_frame_bytes,delta_frame_bytes");
        for (int tick = 1; tick <= 200; tick++) {
            if (tick <= 80) player.setPosition(32 + tick, 32);
            if (tick == 40) state.putEntity(new EntityData(EntityData.Kind.BOMB, 1, 4, 4, "1", 2, 5000));
            if (tick == 100) state.removeEntity("BOMB:1");
            if (tick == 120) state.putEntity(new EntityData(EntityData.Kind.ITEM, 1, 4, 4, "", 0, 0));
            var reference = new StateDeltaBuilder().buildUpdate(state, tick, true);
            var update = builder.buildUpdate(state, tick, tick == 1 || tick % 100 == 0);
            int f = bytes(reference), d = bytes(update);
            fullBytes += f; deltaBytes += d;
            if (update != null) { client.apply(roundTrip(update)); builder.commit(update); packets++; }
            check(client.getTiles().equals(reference.mapTiles()), "map matches full at tick " + tick);
            check(client.getEntities().size() == state.getEntities().size(), "entities match at tick " + tick);
            check(client.getPlayers().get(1).getX() == player.getX(), "player matches at tick " + tick);
            System.out.println(tick + "," + f + "," + d);
        }
        check(deltaBytes < fullBytes, "delta reduces bytes in measured scenario");
        System.out.printf(java.util.Locale.ROOT,
                "PASS: full=%d bytes, delta=%d bytes, saved=%.2f%%, packets=%d/200%n",
                fullBytes, deltaBytes, 100.0 * (1.0 - (double) deltaBytes / fullBytes), packets);
    }
}

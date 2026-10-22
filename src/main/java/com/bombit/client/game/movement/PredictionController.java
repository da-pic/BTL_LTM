package com.bombit.client.game.movement;

import com.bombit.server.game.core.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.*;
import java.util.*;

/** Dự đoán nhân vật local bằng cùng movement/collision server, mỗi mẫu đúng 50 ms.
 * Chỉ gọi trên một UI/simulation thread. Giữ lịch sử hữu hạn để replay khi nhận ACK.
 * Đây là state client riêng, tuyệt đối không truyền Player này cho server runtime.
 */
public final class PredictionController {
    public static final double STEP_SECONDS = 0.05;
    private final GameState predicted;
    private final MovementSystem movement = new MovementSystem();
    private final int playerId;
    private final NavigableMap<Long, MovePacketCodec.Input> pending = new TreeMap<>();
    private long nextSequence = 1;
    private long acknowledged;

    public PredictionController(GameMap map, GameStateUpdate.PlayerData initial) {
        predicted = new GameState(Objects.requireNonNull(map)); playerId = initial.getId();
        Player player = new Player(playerId, initial.getX(), initial.getY(), initial.getWidth(), initial.getHeight(), initial.getSpeed());
        player.setAlive(initial.isAlive()); predicted.addPlayer(player); predicted.startMatch();
        acknowledged = initial.getLastProcessedInput(); nextSequence = acknowledged + 1;
    }
    /** Tạo và mô phỏng input trước khi gửi mạng. Đầy lịch sử thì dừng, không âm thầm xóa input chưa ACK. */
    public MovePacketCodec.Input predict(Direction direction, boolean moving) {
        if (pending.size() >= 256) throw new IllegalStateException("Too many unacknowledged inputs; resync required");
        var input = new MovePacketCodec.Input(nextSequence++, direction, moving);
        pending.put(input.sequence(), input); simulate(input); return input;
    }
    /** Compatibility bridge; new callers use model.Direction. */
    @Deprecated
    public MovePacketCodec.Input predict(Player.Direction direction, boolean moving) {
        return predict(Direction.valueOf(Objects.requireNonNull(direction).name()), moving);
    }
    private void simulate(MovePacketCodec.Input input) {
        Player p = predicted.getPlayer(playerId); p.beginTick();
        movement.applyCommand(predicted, input.moving()
                ? new MoveCommand(playerId, input.direction(), input.sequence())
                : new StopMovingCommand(playerId, input.direction(), input.sequence()));
        movement.update(predicted, STEP_SECONDS);
    }
    /** Reset về ảnh server và replay chỉ input mới hơn ACK. Bỏ ảnh ACK cũ để không lùi lịch sử. */
    public void reconcile(GameStateUpdate.PlayerData authoritative) {
        if (authoritative.getId() != playerId) throw new IllegalArgumentException("Wrong local player");
        long ack = authoritative.getLastProcessedInput();
        if (ack < acknowledged) return;
        acknowledged = ack; nextSequence = Math.max(nextSequence, ack + 1);
        pending.headMap(ack, true).clear();
        Player p = predicted.getPlayer(playerId);
        p.setPosition(authoritative.getX(), authoritative.getY()); p.setSpeed(authoritative.getSpeed());
        p.setAlive(authoritative.isAlive()); p.setDirection(authoritative.direction());
        p.setMoving(authoritative.isMoving()); p.acknowledgeInput(ack);
        // Server đã xác nhận chết: input cũ không còn tác dụng trong trận này.
        if (!authoritative.isAlive()) { pending.clear(); return; }
        for (var input : pending.values()) simulate(input);
    }
    /** Làm mới map/entity trước replay để collision prediction theo trạng thái mới nhất nhận được. */
    public void updateWorld(ClientWorldState replica) {
        GameMap map = predicted.getMap();
        if (map.getWidthInTiles() != replica.getWidth() || map.getHeightInTiles() != replica.getHeight()
                || map.getTileSize() != replica.getTileSize()) throw new IllegalStateException("Map changed; recreate prediction");
        var tiles = replica.getTiles();
        for (int y=0; y<replica.getHeight(); y++) for (int x=0; x<replica.getWidth(); x++)
            map.setTile(x, y, tiles.get(y * replica.getWidth() + x));
        for (var e : predicted.getEntities()) predicted.removeEntity(e.key());
        for (var e : replica.getEntities().values()) predicted.putEntity(e);
    }
    public GameStateUpdate.PlayerData position() {
        Player p = predicted.getPlayer(playerId);
        return new GameStateUpdate.PlayerData(playerId,p.getX(),p.getY(),p.getWidth(),p.getHeight(),p.getSpeed(),
                p.direction(),p.isMoving(),p.isAlive(),acknowledged);
    }
    public int pendingCount() { return pending.size(); }
    /** Discard pre-respawn prediction without reusing any input sequence numbers. */
    public void respawn(){pending.clear();}
}

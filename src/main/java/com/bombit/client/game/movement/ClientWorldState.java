package com.bombit.client.game.movement;

import com.bombit.server.game.model.TileType;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.movement.EntityData;
import com.bombit.server.game.movement.GameStateUpdate;
import java.util.*;

/**
 * Bản sao trạng thái server dùng cho render phía client.
 * Chỉ gọi apply/getter trên cùng một luồng UI hoặc mô phỏng; callback mạng cần chuyển việc sang đó.
 * FULL_STATE thay thế dữ liệu, DELTA ghép thay đổi khi baseTick khớp.
 * Sai baseline thì giữ dữ liệu cũ và yêu cầu full; update cũ bị bỏ qua.
 * Lớp này chưa thực hiện prediction, reconciliation vị trí hoặc interpolation.
 */
public final class ClientWorldState {
    // APPLIED: đã ghép thành công; STALE: update cũ/trùng; NEED_FULL_STATE: không thể ghép an toàn.
    public enum Result { APPLIED, STALE, NEED_FULL_STATE }
    private long tick = -1;
    private int width, height, tileSize;
    private GameState.MatchState matchState;
    private List<TileType> tiles = new ArrayList<>();
    private final Map<Integer, GameStateUpdate.PlayerData> players = new LinkedHashMap<>();
    private final Map<String, EntityData> entities = new LinkedHashMap<>();
    private boolean needsFullState = true;
    private com.bombit.common.dto.BombFrame bombFrame;
    private com.bombit.common.dto.MatchFrame matchFrame;
    public com.bombit.common.dto.MatchFrame getMatchFrame(){return matchFrame;}
    public com.bombit.common.dto.BombFrame getBombFrame() { return bombFrame; }

    // Mục đích: Bỏ update cũ trước, kiểm tra full hoặc baseTick của delta, rồi kiểm tra tile/entity trước khi ghi state. Trả NEED_FULL_STATE để tầng tích hợp yêu cầu phục hồi.
    public Result apply(GameStateUpdate update) {
        Objects.requireNonNull(update);
        // Bước 1: tick chỉ tiến lên trong cùng một trận. Khi chuyển sang trận mới,
        // tạo replica mới vì tick của trận mới có thể bắt đầu lại từ 1.
        if (update.getTick() <= tick) return Result.STALE;
        int w = update.getMapWidth(), h = update.getMapHeight();
        if (w <= 0 || h <= 0 || update.getTileSize() <= 0) return reject();
        // Bước 2: full phải có đủ tile; delta phải khớp cả baseTick, kích thước và tileSize.
        if (update.isFullState()) {
            if ((long) w * h != update.mapTiles().size()) return reject();
        } else if (needsFullState || update.getBaseTick() != tick
                || width != w || height != h || tileSize != update.getTileSize()) return reject();
        // Kiểm tra tọa độ trước khi sửa replica để tránh áp dụng dở một update sai.
        for (var tile : update.getTileChanges()) {
            if (tile.getX() < 0 || tile.getX() >= w || tile.getY() < 0 || tile.getY() >= h) return reject();
        }
        for (var entity : update.getEntityChanges()) {
            if (entity.tileX() >= w || entity.tileY() >= h) return reject();
            if(entity.kind()==EntityData.Kind.ITEM)try{com.bombit.server.game.item.ItemType.fromWireId(entity.variant());}
                catch(IllegalArgumentException unknown){return reject();}
        }
        if(update.getMatchFrame()!=null){
            Set<Integer> expected=new HashSet<>();
            if(update.isFullState())update.getPlayers().forEach(p->expected.add(p.getId()));
            else {expected.addAll(players.keySet());update.getPlayerChanges().forEach(p->expected.add(p.getId()));expected.removeAll(update.getRemovedPlayerIds());}
            if(!expected.equals(update.getMatchFrame().players().stream().map(com.bombit.common.dto.MatchFrame.Stats::playerId).collect(java.util.stream.Collectors.toSet())))return reject();
        }
        if(update.getBombFrame()!=null) for(var effect:update.getBombFrame().explosions()) for(var cell:effect.cells())
            if(cell.x()<0||cell.y()<0||cell.x()>=w||cell.y()>=h) return reject();
        // Bước 3: sau khi kiểm tra, full xóa dữ liệu cũ, còn delta chỉ sửa phần được nêu.
        if (update.isFullState()) {
            tiles = new ArrayList<>(update.mapTiles());
            players.clear(); entities.clear();
            for (var player : update.getPlayers()) players.put(player.getId(), player);
        } else {
            for (var tile : update.getTileChanges()) tiles.set(tile.getY() * w + tile.getX(), tile.tileType());
            for (var player : update.getPlayerChanges()) players.put(player.getId(), player);
            for (int id : update.getRemovedPlayerIds()) players.remove(id);
        }
        // Bước 4: upsert entity và xử lý khóa xóa, sau đó mới công bố tick đã áp dụng.
        for (var entity : update.getEntityChanges()) entities.put(entity.key(), entity);
        for (String key : update.getRemovedEntityKeys()) entities.remove(key);
        width = w; height = h; tileSize = update.getTileSize();
        matchState = update.getMatchState(); tick = update.getTick(); needsFullState = false;
        bombFrame=update.getBombFrame();
        matchFrame=update.getMatchFrame();
        return Result.APPLIED;
    }
    // Mục đích: Đánh dấu replica cần full và giữ dữ liệu hiển thị cũ. Lớp không tự gửi mạng; caller chịu trách nhiệm yêu cầu server.
    private Result reject() { needsFullState = true; return Result.NEED_FULL_STATE; }
    // Mục đích: Cho tầng client biết cần lấy full; không đồng nghĩa một request mạng đã được gửi.
    public boolean needsFullState() { return needsFullState; }
    // Mục đích: Đọc Tick để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public long getTick() { return tick; }
    // Mục đích: Đọc Width để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getWidth() { return width; }
    // Mục đích: Đọc Height để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getHeight() { return height; }
    // Mục đích: Đọc TileSize để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getTileSize() { return tileSize; }
    // Mục đích: Đọc MatchState để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public GameState.MatchState getMatchState() { return matchState; }
    // Mục đích: Đọc Tiles để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<TileType> getTiles() { return List.copyOf(tiles); }
    public Map<Integer, GameStateUpdate.PlayerData> getPlayers() { return Map.copyOf(players); }
    public Map<String, EntityData> getEntities() { return Map.copyOf(entities); }
}

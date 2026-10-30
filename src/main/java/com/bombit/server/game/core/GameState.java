
package com.bombit.server.game.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Trạng thái có thẩm quyền của một trận trên server.
 * Quản lý map, player, trạng thái trận và dữ liệu entity dùng để đồng bộ.
 * Khởi tạo trước khi chạy loop; sau đó chỉ luồng GameLoop được sửa gameplay.
 * Danh sách không sửa được cấu trúc không làm Player bên trong trở thành bất biến.
 * EntityData là ảnh dữ liệu để truyền; luật bom/item vẫn thuộc các hệ thống TV3.
 */
public class GameState {
    private final List<com.bombit.common.dto.PlantRequestResult> plantResults=new ArrayList<>();
    public void recordPlantResult(com.bombit.common.dto.PlantRequestResult result){if(plantResults.size()>=256)throw new IllegalStateException("Plant result backlog");plantResults.add(Objects.requireNonNull(result));}
    public List<com.bombit.common.dto.PlantRequestResult> plantResults(){return List.copyOf(plantResults);}
    public void clearPlantResults(){plantResults.clear();}
    private long nextEventId;
    public void recordGameEvent(com.bombit.server.game.event.GameEvent event){
        addGameNotice(new com.bombit.common.dto.GameNotice(++nextEventId,event));
    }
    private com.bombit.common.dto.MatchFrame matchFrame;
    // Ảnh luật đội do game thread tạo; không nhét winningTeam vào winnerPlayerId của Classic.
    private com.bombit.common.dto.ShadowFrame shadowFrame;
    public com.bombit.common.dto.ShadowFrame getShadowFrame(){return shadowFrame;}
    public void setShadowFrame(com.bombit.common.dto.ShadowFrame frame){shadowFrame=Objects.requireNonNull(frame);}
    private com.bombit.common.dto.ShadowScanFrame shadowScans;
    public com.bombit.common.dto.ShadowScanFrame getShadowScans(){return shadowScans;}
    public void setShadowScans(com.bombit.common.dto.ShadowScanFrame frame){shadowScans=Objects.requireNonNull(frame);}
    public com.bombit.common.dto.MatchFrame getMatchFrame(){return matchFrame;}
    public void setMatchFrame(com.bombit.common.dto.MatchFrame frame){matchFrame=frame;}
    private final List<com.bombit.common.dto.GameNotice> gameNotices=new ArrayList<>();
    public void addGameNotice(com.bombit.common.dto.GameNotice notice) {
        if(gameNotices.size()>=4096) throw new IllegalStateException("Bomb notice backlog full");
        gameNotices.add(Objects.requireNonNull(notice));
    }
    public List<com.bombit.common.dto.GameNotice> getGameNotices() { return List.copyOf(gameNotices); }
    public void clearGameNotices() { gameNotices.clear(); }
    private com.bombit.common.dto.BombFrame bombFrame;
    public com.bombit.common.dto.BombFrame getBombFrame() { return bombFrame; }
    public void setBombFrame(com.bombit.common.dto.BombFrame frame) { bombFrame = frame; }
    // Sau khi trận bắt đầu, chỉ sửa dữ liệu này trên luồng GameLoop.
    private final Map<String, com.bombit.server.game.movement.EntityData> entities = new LinkedHashMap<>();

    // Mục đích: Thêm hoặc thay ảnh entity theo khóa sau khi kiểm tra tile nằm trong map. Đây là dữ liệu đồng bộ; không tự đặt bom, gây damage hoặc áp dụng item.
    public void putEntity(com.bombit.server.game.movement.EntityData entity) {
        Objects.requireNonNull(entity);
        if (!gameMap.isInsideMap(entity.tileX(), entity.tileY())) {
            throw new IllegalArgumentException("Entity outside map");
        }
        entities.put(entity.key(), entity);
    }

    // Mục đích: Xóa ảnh entity theo khóa; builder sẽ phát khóa bị xóa trong delta tiếp theo nếu nó đã tồn tại ở baseline.
    public void removeEntity(String key) { entities.remove(key); }

    // Mục đích: Đọc Entities để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<com.bombit.server.game.movement.EntityData> getEntities() {
        return List.copyOf(entities.values());
    }

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

    // Mục đích: Khởi tạo GameState và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
    public GameState(GameMap gameMap) {
        this.gameMap = Objects.requireNonNull(
                gameMap, "GameMap khong duoc null");
    }

    // Mục đích: Đọc Map để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public GameMap getMap() {
        return gameMap;
    }

    // Mục đích: Đọc MatchState để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public MatchState getMatchState() {
        return matchState;
    }

    // Mục đích: Đọc PlayerCount để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public int getPlayerCount() {
        return players.size();
    }

    //Thêm Player nếu ID đó chưa có trong gamestate hiện tại
    // Mục đích: Chỉ nhận player trong giai đoạn WAITING và trả false nếu trùng ID. Cần kiểm tra kết quả để tránh tưởng rằng đã thêm đủ người.
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
    // Mục đích: Đọc Player để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public Player getPlayer(int playerId) {
        return players.get(playerId);
    }

    //Xoá Player theo ID
    // Mục đích: Xóa player khỏi state; khi trận đang chạy phải thực hiện trên GameLoop để tránh thay đổi collection trong lúc update.
    public boolean removePlayer(int playerId) {
        return players.remove(playerId) != null;
    }

    //Trả về danh sách ko thể xoá/thêm Player trực tiếp
    // Mục đích: Đọc Players để quan sát trạng thái; phương thức không cập nhật mô phỏng.
    public List<Player> getPlayers() {
        return Collections.unmodifiableList(
                new ArrayList<>(players.values()));
    }

    //Bắt đầu trận
    // Mục đích: Chuyển WAITING sang RUNNING khi có ít nhất một player. Điều kiện lobby/ready và số người tối thiểu thực tế do tầng phòng kiểm tra.
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
    // Mục đích: Chuyển sang FINISHED và xóa ý định di chuyển. Việc dừng thread GameLoop là thao tác riêng của tầng quản lý vòng đời.
    public void finishMatch() {
        matchState = MatchState.FINISHED;

        for (Player player : players.values()) {
            player.setMoving(false);
        }
    }
}

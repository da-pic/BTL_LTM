# TV2 — Contract mới và chuyển đổi tương thích

Đây là hướng dẫn runtime chuẩn của TV2; tài liệu và demo runtime cũ đã được dọn.
Không thay đổi implementation network, session, room, bomb/item, common/protocol hoặc bootstrap.

Theo `phancong/nhiemvu.md`, TV2 vẫn phụ trách `client/game/movement/**` (input,
prediction, reconciliation, interpolation). Lệnh cấm `client/**` trong kế hoạch mới cần
được cả nhóm sửa lại phạm vi; không mặc định bàn giao phần này cho TV4.
Xem [tài liệu họp nhóm TV2](TV2_TEAM_ALIGNMENT.md) để chốt quyền sở hữu, protocol,
lifecycle, gameplay extension và tiêu chí nghiệm thu.

## API dùng cho feature mới

- `server/game/model`: Direction, TileType, TilePosition bất biến.
- `server/game/core`: MoveCommand, StopMovingCommand, PlantBombCommand,
  PlayerDisconnectedCommand, PlayerReconnectedCommand.
- `server/game/runtime`: PlayerSpawn, GameplayExtension, GameRuntime, GameRuntimeManager.

```java
try (var games = new com.bombit.server.game.runtime.GameRuntimeManager()) {
    var game = games.startGame("room-1", java.util.List.of(
        new com.bombit.server.game.runtime.PlayerSpawn("uuid-1", 1, 32, 32, 24, 24, 150),
        new com.bombit.server.game.runtime.PlayerSpawn("uuid-2", 2, 96, 32, 24, 24, 150)
    ));
    games.submit("room-1", new com.bombit.server.game.core.MoveCommand(
        1, com.bombit.server.game.model.Direction.RIGHT));
    // Worker TV4 đọc game.pollUpdate(timeout, unit), encode và broadcast theo room.
    // Khi xong: games.stopGame("room-1"); close() cũng dừng tất cả runtime.
}
```

Manager mặc định tạo map mới 17×15 có tường biên, tối đa bốn player. Có thể inject
mapFactory và extensionFactory qua constructor. Factory phải trả map mới từng trận;
manager từ chối tái sử dụng cùng instance để tránh hai phòng sửa chung map.
Spawn được kiểm tra trùng ID/session, biên, wall/block trước khi tạo thread.

## Thứ tự và quyền sở hữu

GameLoop: command FIFO → movement → extension.update(nowMillis) → full/delta → ngủ.
extension.processCommand chạy theo đúng thứ tự lấy command; TV2 chỉ chuyển PlantBombCommand,
không tự đặt bom. Extension factory nhận state một lần để TV3 tạo world adapter, không để
network giữ state này. Extension không được block vô hạn và phải đáp ứng interrupt khi stop.
Lỗi extension được lưu tại runtime.failure(), loop dừng và xóa hàng đợi command.

Queue input hữu hạn, offer không block và đếm queue-full qua rejectedCommandCount().
Command gửi lúc runtime đóng bị từ chối nhưng không tính là queue-full. Mẫu có sequence>0
được mô phỏng tối đa một mẫu/player/tick; mẫu tiếp theo ở đầu queue chờ tick sau để giữ FIFO
toàn cục. TV4 cần rate limit để client spam không làm tăng độ trễ cho cả phòng.

Snapshot bất biến được tạo trên loop. createUpdate() chỉ trả ảnh đã công bố gần nhất,
có thể null trước tick đầu và có thể là delta; không dùng API này để lấy full khi join.
Worker phải đọc pollUpdate theo thứ tự để không mất chuỗi baseline; reconnect/resync gọi
requestFullState rồi đợi full. Không chia sẻ mutable GameState ra network.

## Các quyết định tương thích cần nhóm biết

1. GameCommand là **sealed class**, không phải interface (kế hoạch cho phép lựa chọn).
   Giữ constructor cũ có @Deprecated để client hiện tại vẫn biên dịch; code mới dùng subclass.
   Getter final, payload final và subclass final giữ tính bất biến sau enqueue.
2. Direction/TileType bên trong Player/GameMap và snapshot đã dùng model chung.
   Player.Direction, GameMap.TileType, getDirection/getTile/getFullMap còn là cầu nối cũ.
   Code mới dùng player.direction(), map.tileAt(), snapshot.mapTiles(), playerData.direction()
   và tileChange.tileType(). Khi các thành viên chuyển xong mới xóa cầu nối.
   Client movement, codec TV2 và demo/check hiện đã chuyển sang model chung.
   Tạo full snapshot mới bằng `GameStateUpdate.fullStateModel(...)` với
   `List<model.TileType>`; `fullState(...)` vẫn nhận enum cũ để giữ tương thích.
   `MovePacketCodec.Input.direction()` trả `model.Direction`; constructor nhận
   `Player.Direction` và overload `PredictionController.predict` cũ vẫn được giữ.
   Layout snapshot v2 và JSON MOVE không đổi; OUT_OF_BOUNDS không được lưu/truyền như tile nền.
3. Giữ int playerId nội bộ để không tự đổi wire schema/client ngoài phạm vi TV2.
   UUID String nằm ở PlayerSpawn.sessionId; game.playerId(uuid) là ánh xạ bất biến của trận.
   Handler phải lấy UUID từ session xác thực, không tin số ID trong payload client.
4. Chỉ còn manager trong game.runtime; registry và runtime cũ trong core đã xóa.
   MovementHandler chỉ nhận runtime manager chuẩn.
5. Adapter có luật item và adapter session cũ đã xóa. TV3 implement GameplayExtension;
   TV1 gửi PlayerDisconnectedCommand/PlayerReconnectedCommand qua submit.
6. Codec đề xuất TV2 và client giữ nguyên định dạng v2 trong đợt này, để TV4 tiếp quản;
   dữ liệu server model đổi nhưng thứ tự byte và benchmark không đổi.
7. Đã xóa worldTask/freeze/resume: lifecycle đi qua command FIFO, gameplay đi qua extension.

## Kiểm thử và bàn giao

Chạy `cmd /c scripts\check-tv2.cmd` để biên dịch toàn bộ production và demo/check, chạy
movement/TCP, runtime contract và delta regression. Có thể chạy riêng bộ contract:

```bat
java -cp target/tv2-check com.bombit.server.game.runtime.RuntimeContractCheck
```

RuntimeContractTest cũng gọi bộ này qua JUnit khi có Maven. Bao gồm: FIFO, hai producer
100 command mỗi thread, queue đầy và bộ đếm, start/stop, không sót thread, hai phòng độc lập,
wall/block/boundary/dead player, snapshot không đổi sau world mutation, lỗi extension.

Việc còn lại của giai đoạn 0 toàn nhóm: TV3 đổi model bomb/item,
TV4 duyệt schema/codec và đăng ký handler; người tích hợp nối manager mới
với lobby/start game. Không coi các cầu nối là bằng chứng toàn nhóm đã hoàn tất migration.

# Kế hoạch Thành viên 3 — Bomb, Explosion, Item & Game Events (Duy)

## 1. Mục tiêu và phạm vi

Thành viên 3 chịu trách nhiệm toàn bộ luật gameplay liên quan đến bom, vụ nổ, block phá được, sát thương, vật phẩm và các hiệu ứng hiển thị tương ứng trên Client.

Module chính:

- `server/game/bomb`: đặt bom, quản lý fuse, tính vùng nổ và chain reaction.
- `server/game/item`: sinh vật phẩm, xác thực nhặt vật phẩm và áp dụng hiệu ứng.
- `server/game/event`: mô hình event gameplay độc lập với protocol mạng.
- `client/game/bomb`: trạng thái hiển thị bom và animation vụ nổ.
- `client/game/item`: trạng thái hiển thị vật phẩm.

Không thuộc phạm vi của Thành viên 3:

- Không tự viết Game Loop hoặc sửa trực tiếp cấu trúc `GameState`; phần này thuộc TV2.
- Không tự định nghĩa `PacketType`, encoder/decoder hoặc Socket; phần này thuộc TV4 và TV1.
- Không broadcast trực tiếp bằng Socket; chỉ phát `GameEvent` hoặc gọi cổng broadcast đã thống nhất.
- Không tin tọa độ, `playerId`, sức mạnh bom hoặc trạng thái sống do Client gửi lên.

## 2. Phân tích kiến trúc

Luồng xử lý chuẩn:

```text
Client gửi PLANT_BOMB
        |
Message Router (TV4)
        |
BombHandler xác thực Session/Room (TV1)
        |
Đưa PlantBombCommand vào Command Queue (TV2)
        |
Game Loop gọi BombSystem
        |
Cập nhật GameState qua API của TV2
        |
Sinh GameEvent
        |
Protocol + Room Broadcast (TV4 + TV1)
        |
Client cập nhật renderer
```

Nguyên tắc concurrency bắt buộc: callback của `ScheduledExecutorService` không được sửa `GameState` trực tiếp. Callback chỉ đánh dấu bom đến hạn hoặc đưa `ExplodeBombCommand` vào queue; Game Loop mới thực hiện nổ bom, phá block, gây sát thương và sinh item. Cách này giữ `GameState` trên một luồng cập nhật duy nhất và tránh race condition với movement.

## 3. Hợp đồng cần chốt trước khi code

### Với TV2 — Game Core / Movement

Cần thống nhất các API tối thiểu sau, đặt tại `server/game/api` hoặc package do TV2 quyết định:

- Đọc Player theo `playerId`, trạng thái sống, vị trí hiện tại và chỉ số gameplay.
- Quy đổi tọa độ Player sang ô tile; kiểm tra ô hợp lệ để đặt bom.
- Đọc loại tile: `EMPTY`, `HARD_WALL`, `BREAKABLE_BLOCK`.
- Phá một `BREAKABLE_BLOCK` thông qua Game Loop.
- Đọc danh sách Player trong một ô/vùng nổ và đánh dấu Player chết.
- Thêm, đọc, xóa Bomb và Item trong state hoặc qua repository/view do TV2 cung cấp.
- Cập nhật chỉ số Player: giới hạn bom, tầm nổ, tốc độ.
- Xác định điều kiện `GAME_OVER` sau khi Player chết.

Các quy tắc đã chốt:

- Mỗi ô chỉ được chứa tối đa một bomb. Yêu cầu đặt bomb vào ô đã có bomb phải bị từ chối với lỗi `CELL_OCCUPIED`.
- Explosion đi tới ô có `BREAKABLE_BLOCK`, phá block đó rồi dừng ở ô này; không lan sang các ô phía sau block.
- Player đặt bomb được phép bước ra khỏi chính ô đang đứng để không bị mắc kẹt. Ngay khi đã rời ô, bomb trở thành vật cản và Player không thể bước ngược vào. Các Player khác luôn coi bomb là vật cản.

Các quy tắc còn cần thống nhất:

- Hệ tọa độ tile, `tileSize`, cách làm tròn vị trí Player khi đặt bom.
- Thứ tự xử lý movement, pickup, explosion và death trong cùng một tick.

### Với TV4 — Protocol / Dispatcher

`docs/protocol.md` và `docs/thanhvien4.md` hiện chưa thống nhất tên message. Cần chốt một tên duy nhất cho từng event:

- `BOMB_PLACED` hoặc `BOMB_PLANTED`.
- `ITEM_PICKUP` hoặc `ITEM_PICKED_UP`.
- Có dùng `BLOCK_DESTROYED` riêng hay đưa danh sách block bị phá vào `BOMB_EXPLODE`.

Payload đề xuất:

- `PLANT_BOMB`: `requestId`; Server lấy Player và vị trí từ Session/GameState.
- `BOMB_PLANTED`: `bombId`, `ownerPlayerId`, `x`, `y`, `fuseMs`, `range`.
- `BOMB_EXPLODE`: `bombId`, `x`, `y`, `affectedCells`, `destroyedBlocks`.
- `ITEM_SPAWN`: `itemId`, `itemType`, `x`, `y`.
- `ITEM_PICKED_UP`: `itemId`, `playerId`, `itemType`, `newValue` nếu Client cần hiển thị chỉ số.
- `PLAYER_DIE`: `playerId`, `cause`, `killerPlayerId` có thể rỗng.
- `ERROR`: `errorCode`, thông báo an toàn và `requestId` để ghép với yêu cầu đặt bom.

### Với TV1 — Session / Room

Cần API để:

- Xác nhận Session đang ở trạng thái `PLAYING` và thuộc đúng Room.
- Lấy `playerId` từ Session thay vì từ payload.
- Phát event tới đúng Room.
- Hủy timer/tài nguyên bomb khi Room hoặc trận đấu bị hủy.

## 4. Mô hình dữ liệu đề xuất

### Bomb

- `bombId`: ID duy nhất trong trận.
- `ownerPlayerId`: người đặt bom.
- `tileX`, `tileY`: vị trí ô do Server quyết định.
- `placedAtNanos`: mốc thời gian đơn điệu.
- `fuseMs`: thời gian chờ nổ.
- `range`: tầm nổ tại lúc đặt bom.
- `state`: `ARMED`, `QUEUED_TO_EXPLODE`, `EXPLODED`, `CANCELLED`.

### Item

- `itemId`: ID duy nhất trong trận.
- `type`: `BOMB_UP`, `FIRE_UP`, `SPEED_UP`.
- `tileX`, `tileY`: vị trí vật phẩm.
- `state`: `AVAILABLE`, `PICKED_UP`, `REMOVED`.

### GameEvent

- `eventId`: ID giúp log và kiểm thử.
- `roomId` hoặc `gameId`.
- `type`: loại sự kiện nghiệp vụ.
- `occurredAt`: thời điểm sự kiện.
- Payload bất biến tương ứng với từng loại event.

Event nghiệp vụ không nên phụ thuộc trực tiếp vào `Packet` để phần logic có thể unit test mà không cần mạng.

## 5. Kế hoạch triển khai

### Giai đoạn 1 — Chốt luật và API tích hợp

1. Chốt bảng luật bomb/item với TV2: fuse, range, block, chain reaction, damage và pickup.
2. Chốt tên message và payload với TV4; cập nhật `docs/protocol.md` trước khi tích hợp mạng.
3. Chốt API state mutation và command queue với TV2.
4. Chốt API broadcast theo Room và vòng đời trận với TV1.

Đầu ra: tài liệu contract được duyệt, không còn tên message hoặc kiểu ID mâu thuẫn.

### Giai đoạn 2 — Domain model và logic đặt bom

1. Tạo `Bomb`, `BombState` và ID generator theo phạm vi một trận.
2. Tạo `PlantBombCommand` hoặc contract tương đương.
3. Cài `BombHandler` chỉ làm xác thực đầu vào và enqueue command.
4. Cài `BombSystem.plantBomb(...)` với các kiểm tra:
   - Player tồn tại, còn sống và đang chơi.
   - Ô đặt bom nằm trong map và không bị chiếm bởi bom khác.
   - Player chưa vượt giới hạn bom đang hoạt động.
5. Khi thành công, thêm bomb vào state và sinh `BOMB_PLANTED`.
6. Khi thất bại, trả `ERROR` có mã rõ ràng như `PLAYER_NOT_ALIVE`, `BOMB_LIMIT_REACHED`, `CELL_OCCUPIED`.

Đầu ra: đặt bom đúng ô, chống request lặp bằng `requestId` nếu nhóm triển khai idempotency.

### Giai đoạn 3 — Fuse và Explosion System

1. Dùng một `ScheduledExecutorService` dùng chung cho trận hoặc kiểm tra deadline theo Game Loop; không tạo một Thread cho mỗi bomb.
2. Khi hết fuse, enqueue yêu cầu nổ vào Game Loop.
3. Tính vùng nổ từ tâm theo bốn hướng với thứ tự xác định.
4. Dừng trước `HARD_WALL`; gồm ô `BREAKABLE_BLOCK` rồi dừng hướng đó.
5. Xử lý chain reaction bằng queue/set bomb ID để mỗi bomb chỉ nổ một lần.
6. Xóa bomb khỏi state, hoàn lại slot đặt bom cho chủ sở hữu và sinh `BOMB_EXPLODE`.

Đầu ra: vùng nổ xác định, không nổ lặp, không sửa state từ scheduler thread.

### Giai đoạn 4 — Block, damage và kết thúc trận

1. Phá block qua API của TV2 và ghi nhận danh sách block bị phá.
2. Kiểm tra Player nằm trong các ô nổ theo quy tắc hitbox đã chốt.
3. Đánh dấu chết đúng một lần và sinh `PLAYER_DIE`.
4. Ghi nhận `killerPlayerId` theo owner của bomb, kể cả tự sát nếu luật cho phép.
5. Yêu cầu TV2/Game Core đánh giá điều kiện `GAME_OVER` sau khi xử lý toàn bộ explosion trong tick.

Đầu ra: block và Player được cập nhật nhất quán trước khi event được broadcast.

### Giai đoạn 5 — Item System

1. Tạo `Item`, `ItemType` và `ItemSystem`.
2. Khi block bị phá, quyết định spawn bằng chiến lược có thể test được; nên inject RNG hoặc seed.
3. Không spawn item trên ô không hợp lệ hoặc ô đã chứa item.
4. Server tự phát hiện pickup từ vị trí Player trong Game Loop; không tin yêu cầu pickup tùy ý từ Client.
5. Áp dụng hiệu ứng có giới hạn:
   - `BOMB_UP`: tăng số bom tối đa.
   - `FIRE_UP`: tăng range.
   - `SPEED_UP`: tăng tốc độ nhưng không vượt trần.
6. Xóa item và sinh `ITEM_PICKED_UP` đúng một lần.

Đầu ra: spawn/pickup có tính quyết định trong test, không xảy ra double pickup.

### Giai đoạn 6 — Client rendering và tích hợp mạng

1. Client gửi `PLANT_BOMB` qua `ClientNetwork` của TV4.
2. `BombRenderer` quản lý hiển thị dựa trên `BOMB_PLANTED` và `BOMB_EXPLODE`.
3. `ExplosionRenderer` chạy animation theo `affectedCells`, không tự tính lại vùng nổ.
4. `ItemRenderer` thêm/xóa item theo event từ Server.
5. Khi nhận snapshot/full sync, reconcile danh sách bomb/item để phục hồi sau mất event hoặc reconnect.
6. Animation chỉ là biểu diễn; state thật luôn theo Server.

Đầu ra: hai Client trong cùng Room nhìn thấy cùng vị trí bomb, explosion, item và Player chết.

### Giai đoạn 7 — Kiểm thử và hoàn thiện

Unit test tối thiểu:

- Đặt bom hợp lệ và các trường hợp bị từ chối.
- Vùng nổ ở giữa map, mép map, gặp hard wall và breakable block.
- Chain reaction hai hoặc nhiều bomb; mỗi bomb chỉ nổ một lần.
- Hoàn lại bomb slot sau khi nổ.
- Một explosion giết nhiều Player nhưng mỗi Player chỉ có một death event.
- Item spawn với RNG cố định; pickup áp dụng đúng chỉ số và không pickup hai lần.
- Scheduler chỉ enqueue, không trực tiếp mutation state.

Integration test tối thiểu:

- `PLANT_BOMB` đi qua Router → Queue → Game Loop → Room event.
- Hai Client nhận event theo cùng thứ tự trong một Room.
- Room khác không nhận event.
- Disconnect/hủy Room dọn timer và không phát event muộn.
- Snapshot/full sync khớp với state bomb/item hiện tại.

## 6. Thứ tự PR đề xuất

1. `feature/tv3-domain-contracts`: model, interface, rule constants và unit test.
2. `feature/tv3-bomb-explosion`: đặt bom, fuse, explosion, chain reaction và test.
3. `feature/tv3-items-damage`: block, damage/death, item spawn/pickup và test.
4. `feature/tv3-client-events`: adapter protocol và client rendering.
5. `feature/tv3-integration-tests`: test đa Client, Room isolation và cleanup.

Mỗi PR chỉ sửa package thuộc TV3. Nếu cần thay đổi `GameState` hoặc protocol, tạo PR/commit riêng do chủ sở hữu TV2/TV4 duyệt.

## 7. Tiêu chí hoàn thành

- Server là nguồn quyết định duy nhất cho vị trí bomb, thời điểm nổ, vùng nổ, damage và item.
- Không có Thread riêng cho từng bomb và không có mutation `GameState` ngoài Game Loop.
- Bomb nổ đúng một lần; chain reaction kết thúc hữu hạn.
- Block, death và item được cập nhật nguyên tử trong cùng nhịp xử lý gameplay.
- Event chỉ broadcast cho đúng Room và đúng một lần.
- Client chỉ render event, có thể phục hồi từ snapshot/full sync.
- Unit test cho logic thuần và integration test cho luồng mạng đều chạy qua `mvn test`.
- Không sửa trái quyền sở hữu module trong `docs/TEAM_OWNERSHIP.md`.

## 8. Rủi ro cần ưu tiên xử lý

- **Race condition:** scheduler, movement và disconnect cùng tác động state; giải quyết bằng command queue/Game Loop duy nhất.
- **Protocol lệch tên:** `BOMB_PLACED`/`BOMB_PLANTED` và `ITEM_PICKUP`/`ITEM_PICKED_UP`; phải chốt trước khi viết adapter.
- **Duplicate request/event:** TCP không tự loại request nghiệp vụ lặp; cân nhắc lưu `requestId` gần nhất theo Player.
- **Chain reaction vô hạn:** dùng trạng thái bomb và tập `processedBombIds` trong một chu kỳ nổ.
- **Timer leak:** hủy task khi trận hoặc Room kết thúc.
- **Sai khác Client:** Client không tự tính vùng nổ hoặc drop item; luôn dùng kết quả Server.

## 9. Phần đã triển khai trong `src`

Phần code hiện tại chỉ nằm trong phạm vi sở hữu của TV3:

- `server/game/bomb`
- `server/game/item`
- `server/game/event`
- `client/game/bomb`
- `client/game/item`

Không sửa `GameState`, Game Loop, movement, protocol, dispatcher, Socket hoặc bootstrap của thành viên khác.

### 9.1. Bomb domain

Các file chính:

- `Bomb`: lưu ID, chủ sở hữu, vị trí tile, thời điểm đặt, fuse, range và trạng thái vòng đời.
- `BombState`: gồm `ARMED`, `QUEUED_TO_EXPLODE`, `EXPLODED`, `CANCELLED` để chống xử lý một bomb nhiều lần.
- `TilePosition`, `Direction`, `TileType`: kiểu dữ liệu tối thiểu cho thuật toán nổ theo bốn hướng.
- `BombError` và `BombPlacementResult`: trả kết quả đặt bomb rõ ràng mà không phụ thuộc protocol `ERROR` của TV4.

`Bomb` kiểm tra dữ liệu ngay khi khởi tạo: ID, fuse và range phải là số dương. Vị trí và chủ sở hữu không được rỗng.

### 9.2. Cổng tích hợp `BombWorld`

`BombWorld` là interface để TV2 viết adapter nối với `GameState`. TV3 chỉ yêu cầu những thao tác cần thiết như:

- Đọc vị trí, trạng thái sống, bomb limit và range của Player.
- Đọc tile và bomb tại một ô.
- Thêm/xóa bomb.
- Phá block và đánh dấu Player chết.

Nhờ interface này, `BombSystem` có thể unit test độc lập và không cần sửa file thuộc TV2. Khi tích hợp thật, TV2 cần bảo đảm implementation của `BombWorld` chỉ được gọi trên Game Loop.

### 9.3. Đặt bomb

`BombSystem.plantBomb(...)` thực hiện lần lượt:

1. Kiểm tra Player tồn tại.
2. Kiểm tra Player còn sống.
3. Lấy vị trí từ Server, không nhận tọa độ tùy ý từ Client.
4. Kiểm tra tile là `EMPTY`.
5. Kiểm tra ô chưa có bomb, đúng luật một ô tối đa một bomb.
6. Kiểm tra Player chưa vượt bomb limit.
7. Tạo bomb, thêm vào world và phát `BombPlacedEvent`.

Event đặt bomb lưu dữ liệu bất biến gồm ID, chủ sở hữu, vị trí, fuse, thời điểm nổ và range. Event không giữ tham chiếu tới object `Bomb` có trạng thái thay đổi.

### 9.4. Fuse và scheduler

`BombScheduler` dùng một `ScheduledExecutorService` được truyền từ bên ngoài. Mỗi deadline chỉ thực hiện hai việc:

1. Chuyển bomb từ `ARMED` sang `QUEUED_TO_EXPLODE`.
2. Gửi `bombId` vào `explosionCommandSink` để tầng tích hợp đưa vào Command Queue.

Scheduler không gọi `BombSystem.explodeBomb(...)` và không sửa `GameState`. Game Loop phải lấy command rồi mới xử lý vụ nổ. `close()` hủy toàn bộ task đang chờ khi trận đấu kết thúc nhưng không tự tắt executor do executor có thể do composition root quản lý.

### 9.5. Explosion và chain reaction

`BombSystem.explodeBomb(...)` dùng queue để xử lý bomb gốc và các bomb bị kích nổ dây chuyền:

- Tâm bomb luôn thuộc vùng nổ.
- Lửa lan theo `UP`, `RIGHT`, `DOWN`, `LEFT` tới tối đa `range` ô.
- `HARD_WALL` và ngoài bản đồ chặn lửa; ô tường không thuộc vùng nổ.
- `BREAKABLE_BLOCK` thuộc vùng nổ, bị phá rồi chặn toàn bộ các ô phía sau.
- Bomb nằm trong vùng nổ được đưa vào queue chain reaction.
- Tập `processedBombIds` bảo đảm mỗi bomb chỉ nổ một lần và tránh vòng lặp vô hạn.
- Player trong vùng nổ bị đánh dấu chết đúng một lần trong cả chuỗi nổ.

Kết quả được trả bằng `ExplosionResult`, gồm bomb đã nổ, toàn bộ ô ảnh hưởng, block bị phá và Player bị chết. Đồng thời hệ thống phát các event nghiệp vụ để tầng protocol chuyển thành packet.

### 9.6. Item system

Các file chính:

- `Item`, `ItemType`, `ItemState`: model vật phẩm và vòng đời.
- `ItemSpawnPolicy`: chiến lược quyết định có drop item và loại item nào.
- `RandomItemSpawnPolicy`: triển khai random có thể inject seed để test ổn định.
- `ItemWorld`: cổng tích hợp với Player và GameState của TV2.
- `ItemSystem`: spawn, pickup và áp dụng hiệu ứng.

`ItemSystem.trySpawn(...)` không spawn chồng lên item hiện có. `tryPickUp(...)` chỉ cho Player còn sống nhặt item đúng tại vị trí Server xác nhận, sau đó xóa item, áp dụng hiệu ứng và phát event. Trạng thái `PICKED_UP` ngăn hai Player nhặt cùng một item hai lần.

Ba loại item hiện có:

- `BOMB_UP`: tầng tích hợp tăng bomb limit.
- `FIRE_UP`: tầng tích hợp tăng bomb range.
- `SPEED_UP`: tầng tích hợp tăng tốc độ theo giới hạn do TV2 quản lý.

### 9.7. Gameplay event

`GameEvent` là sealed interface cho các event:

- `BombPlacedEvent`
- `BombExplodedEvent`
- `BlockDestroyedEvent`
- `PlayerDiedEvent`
- `ItemSpawnedEvent`
- `ItemPickedUpEvent`

`GameEventSink` là cổng output. Khi tích hợp, TV4/TV1 có thể cung cấp implementation chuyển event thành packet và broadcast tới đúng Room. Domain event không import hoặc phụ thuộc `Packet`.

### 9.8. Hiển thị Java 2D phía Client

Phần hiển thị TV3 đã chốt dùng Java 2D trên AWT/Swing. Renderer không cần file ảnh ngoài, toàn bộ hình được tạo bằng `Graphics2D`, shape, màu và gradient nên có thể chạy ngay trên Java 17.

Các lớp state nhận dữ liệu từ network:

- `BombPresentationState`: thêm/xóa bomb, giữ explosion trong thời gian animation và thay toàn bộ bomb khi nhận snapshot/full sync.
- `ItemPresentationState`: thêm/xóa item và thay toàn bộ item khi nhận snapshot/full sync.

Các lớp vẽ:

- `BombRenderer`: vẽ thân bomb bằng radial gradient, bóng đổ, nắp bomb, dây cháy có spark nhấp nháy và số giây countdown.
- `ExplosionRenderer`: vẽ hiệu ứng lửa theo chính xác `affectedCells` do Server gửi; animation phồng lên rồi thu nhỏ trong khoảng thời gian hiển thị.
- `ItemRenderer`: vẽ ba item `BOMB_UP`, `FIRE_UP`, `SPEED_UP` bằng biểu tượng riêng, viền màu và chuyển động nổi lên/xuống nhẹ.
- `BombItemEffectsPanel`: `JPanel` trong suốt ghép cả ba renderer và repaint khoảng 60 FPS bằng Swing `Timer`.

Thứ tự vẽ của panel là item → bomb → explosion, vì vậy lửa nổ luôn hiển thị trên cùng. Timer tự chạy khi panel được gắn vào Swing hierarchy và tự dừng khi panel bị tháo ra.

Cách gắn panel vào màn hình game:

```java
BombPresentationState bombState = new BombPresentationState();
ItemPresentationState itemState = new ItemPresentationState();

BombItemEffectsPanel effectsPanel = new BombItemEffectsPanel(
        bombState,
        itemState,
        48); // tileSize = 48 pixel
effectsPanel.setMapOffset(0, 0);
effectsPanel.setMapSizeInTiles(15, 13);
```

Khi Client nhận event từ Server, adapter mạng gọi:

```java
bombState.bombPlaced(bombId, position, explodeAtMillis, range);
bombState.bombExploded(bombId, affectedCells, nowMillis, 550);

itemState.itemSpawned(itemId, itemType, position);
itemState.itemPickedUp(itemId);
```

`tileSize` phải giống kích thước ô mà phần bản đồ của TV2/UI đang dùng. `mapOffsetX` và `mapOffsetY` là tọa độ pixel góc trên-trái của bản đồ trong panel. Client chỉ vẽ `affectedCells` nhận từ Server, không tự tính lại vùng nổ hoặc kết quả pickup.

Chạy cửa sổ demo Java 2D độc lập trên Windows PowerShell:

```powershell
$files = rg --files src/main/java -g "*.java"
New-Item -ItemType Directory -Force target/demo-classes | Out-Null
javac --release 25 -d target/demo-classes $files
java -cp target/demo-classes com.bombit.client.game.bomb.BombItemEffectsDemo
```

Demo hiển thị bản đồ mẫu 13×9, ba loại item và một bomb ở giữa. Bomb đếm ngược ba giây, phát nổ theo hình dấu cộng rồi tự tạo lại để có thể quan sát animation liên tục. Đóng cửa sổ bằng nút `X`.

### 9.9. Kiểm thử đã viết

Các test trong `src/test/java` kiểm tra:

- Một ô không thể có hai bomb.
- Explosion phá breakable block và không đi xuyên qua block.
- Chain reaction làm mỗi bomb nổ đúng một lần.
- Player chết chỉ phát một death event.
- Scheduler chỉ enqueue bomb ID.
- Item spawn/pickup đúng một lần và không spawn chồng.
- Client thay bomb bằng explosion tạm thời rồi xóa animation hết hạn.
- Java 2D renderer tạo được pixel hiển thị cho bomb, explosion và item trên `BufferedImage`.

Mã nguồn chính đã được biên dịch thành công bằng `javac --release 17`. Chưa chạy được test JUnit bằng Maven trên máy hiện tại vì lệnh `mvn` chưa được cài hoặc chưa nằm trong `PATH`.

### 9.10. Phần chờ thành viên khác để tích hợp

- TV2 implement `BombWorld` và `ItemWorld` trên `GameState`, đồng thời gọi system từ Game Loop.
- TV2 triển khai collision cho luật: chủ bomb được bước ra khỏi ô vừa đặt, sau khi rời thì không được quay lại; Player khác luôn bị bomb chặn.
- TV4 chuyển các `GameEvent` thành packet sau khi chốt tên message và schema.
- TV1 cung cấp Room broadcaster và gọi cleanup scheduler khi Room/trận bị hủy.
- Người tích hợp hệ thống khởi tạo `BombSystem`, `ItemSystem`, `BombScheduler` tại composition root.

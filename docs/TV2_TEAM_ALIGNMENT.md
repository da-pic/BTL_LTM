# Tài liệu họp nhóm: contract và nghiệm thu phần TV2

Lưu ý cập nhật M0: các câu hỏi kỹ thuật đã có baseline tại [protocol.md](protocol.md) và
[M0_INTEGRATION_CONTRACT.md](M0_INTEGRATION_CONTRACT.md). Nội dung dưới đây giữ làm tài liệu
thảo luận trước đó; phần đề xuất cũ không ghi đè contract mới.

Tài liệu đối chiếu với mã nguồn hiện tại và `phancong/nhiemvu.md`. Mục tiêu là giúp
TV2 (Chinh) thống nhất với TV1 (Mai), TV3 (Duy), TV4 (Giang) trước khi ghép game.
Đây là **đề xuất thảo luận**, không phải biên bản nhóm đã thông qua.

Quy ước: **Đã có** là đã tồn tại trong code; **Cần chốt** là quyết định còn thiếu;
**Đề xuất** là lựa chọn để nhóm xem xét, chưa phải tính năng đã hoàn thành.

## 1. Kết quả cần đạt sau buổi họp

Nhóm cần ghi được một phiên bản contract, một người tích hợp và người chịu trách nhiệm
cho từng đầu việc. Tránh kết thúc chỉ với câu “cứ code rồi ghép sau”.

| Ưu tiên | Quyết định cần chốt | Người cùng quyết định | Vì sao cần chốt sớm |
|---|---|---|---|
| P0 | TV2 sở hữu cả server movement và client movement | Cả nhóm | Hai bản phân công hiện mâu thuẫn |
| P0 | Định danh player, room và phiên trận | TV1 + TV2 + TV4 | Chặn điều khiển nhầm người/phòng và nhận nhầm packet trận cũ |
| P0 | Schema MOVE, input sequence và ACK | TV2 + TV4 | Prediction/reconciliation phụ thuộc trực tiếp |
| P0 | Start game, snapshot đầu và người đọc outbound queue | TV1 + TV2 + TV4 | Không được mất full đầu hoặc chia mất chuỗi delta |
| P0 | API world và cách chạy bom/item trên GameLoop | TV2 + TV3 | Tránh race condition, tránh TV3 phải sửa loop |
| P0 | Disconnect/reconnect/resync và xử lý input cũ | TV1 + TV2 + TV4 | Full snapshot không tự giải quyết input đang trên đường truyền |
| P1 | Luật tọa độ, collision, speed, spawn | TV2 + TV3 | Client/server phải mô phỏng cùng một luật |
| P1 | Event so với snapshot và cách render | TV2 + TV3 + TV4 | Tránh event áp dụng hai lần hoặc khôi phục state sai |
| P1 | Người tích hợp bootstrap và thứ tự merge | Cả nhóm | Một luồng ghép rõ ràng, không sửa bootstrap song song |
| P1 | Kịch bản nghiệm thu và cách đo delta | Cả nhóm, TV4 tổng hợp | Phân biệt số đo mô phỏng với bằng chứng mạng thực |

## 2. Thống nhất lại quyền sở hữu trước tiên

Theo nhiệm vụ ban đầu, TV2 phải làm prediction, reconciliation và interpolation phía
client. Kế hoạch mới cấm toàn bộ `client/**` nhưng không giao những chức năng đó cho ai;
nhóm cần sửa phạm vi thay vì mặc định chúng đã chuyển sang TV4.

| Thành viên | Phạm vi đề xuất |
|---|---|
| TV1 | Connection, session, room/lobby, chat, heartbeat, xác thực reconnect |
| TV2 | `server/game/model`, `core`, `movement`, `runtime`; `client/game/movement`; test tương ứng |
| TV3 | Bomb, explosion, item, event gameplay; client renderer/animation bomb và item |
| TV4 | Protocol, encoder/decoder, router, ClientNetwork, metrics và tổng hợp kiểm thử mạng |
| Người tích hợp do nhóm chỉ định | ServerApplication, ClientApplication và ghép UI/lobby/gameplay |

TV2 dùng `ClientNetwork`; không tự viết socket production riêng. TV3 dùng world/extension;
không tự sửa GameLoop. Codec movement/snapshot TV2 đang có là đề xuất để TV4 duyệt và
chọn nơi sở hữu chính thức, không duy trì hai codec cho cùng một message.

**Câu cần hỏi cả nhóm:** “Nhóm xác nhận TV2 được sửa `client/game/movement/**` và test
của phần này chưa? Ai ghép cửa sổ gameplay với lobby và ai sở hữu codec cuối cùng?”

## 3. Làm việc với TV1 — kết nối, phòng và vòng đời trận

### 3.1. ID và phiên trận

**Đã có:** Session dùng UUID String; Player/GameCommand/snapshot dùng ID int trong trận.
`PlayerSpawn(sessionId, playerId, x, y, width, height, speed)` lưu ánh xạ;
`runtime.playerId(sessionUuid)` tra ID nội bộ. MOVE không được tự khai playerId.

**Cần chốt:**

- Giữ ánh xạ hiện tại hay chuyển String xuyên suốt? Nếu chuyển, phải đổi đồng thời
  server, snapshot, codec và client; không đổi riêng một module.
- Ai cấp ID int, cấp lúc nào và giữ nguyên tới khi nào? Đề xuất người tạo runtime cấp
  một lần, không tái sử dụng ID của người rời trong cùng trận.
- Một room có chơi lại nhiều trận không? Đề xuất có `matchId` hoặc epoch riêng từng trận.
  Hiện snapshot chưa có định danh trận để client phân biệt tick 1 của hai lần chơi.
- CONNECT_ACK/GAME_STARTED phải cung cấp ID nào để client biết nhân vật local?

**Đề xuất ít sửa nhất:** giữ UUID ngoài mạng + ID int nội bộ, gửi rõ mapping cần thiết và
matchId trong handshake bắt đầu trận. Đây chưa phải schema đã triển khai.

### 3.2. Luồng bắt đầu game

Luồng cần cùng thống nhất:

```text
TV1 kiểm tra phòng/ready → chốt danh sách player và spawn
→ TV2 startGame(roomId, spawns)
→ thông báo GAME_STARTED kèm định danh trận và local player
→ worker lấy snapshot đầu theo đúng thứ tự
→ TV1/TV4 broadcast FULL_STATE
→ client khởi tạo replica/prediction và mới mở input
```

Chốt ai chịu trách nhiệm bảo đảm GAME_STARTED tới trước snapshot và listener đã đăng ký
trước full đầu. Nếu startGame thất bại, room phải quay về trạng thái hợp lệ; không để
session PLAYING trong khi runtime không tồn tại. TV1/TV4 cần quy định rollback cụ thể.

**Đã có:** manager tạo state/loop riêng mỗi room, kiểm tra spawn và trùng ID, tối đa 4 player.
Map mặc định 17×15, tile 32 pixel, có tường biên; chưa coi đó là map gameplay đã chốt.

### 3.3. Disconnect, rời phòng và kết thúc

| Tình huống | TV1 làm gì | TV2 cần nhận gì |
|---|---|---|
| Mất kết nối tạm thời | Đánh dấu session, giữ room trong thời gian chờ nếu nhóm chọn reconnect | PlayerDisconnectedCommand để dừng movement |
| Hết thời hạn reconnect/rời hẳn | Dọn session/room theo luật | PlayerDisconnectedCommand với remove=true |
| Reconnect hợp lệ | Xác thực token và gắn kết nối mới | PlayerReconnectedCommand và yêu cầu full |
| Phòng rỗng/kết thúc trận | Dừng worker broadcast và dọn phòng đúng thứ tự | stopGame(roomId) để giải phóng thread |
| Runtime lỗi | Đọc runtime.failure(), thông báo các client và đóng trận | Không tiếp tục gửi delta của trận lỗi |

**Cần chốt:** người chơi mất mạng có còn bị bom gây damage không; giữ bao lâu; trận có
tiếp tục hay kết thúc; thứ tự gửi command trước khi xóa roomId; xử lý khi queue từ chối
lệnh disconnect. Không âm thầm bỏ command lifecycle vì queue đầy.

**Điểm chưa giải quyết:** reconnect phải xử lý mẫu MOVE cũ đã nằm trong queue hoặc trên
kết nối cũ. Đề xuất input epoch mới khi reconnect, hoặc quy trình flush/cutoff được định
nghĩa rõ. Chỉ reset sequence về 1 hoặc chỉ gọi requestFullState đều chưa đủ.

## 4. Làm việc với TV3 — gameplay extension và world

### 4.1. Luồng sửa state

**Đã có:**

```java
public interface GameplayExtension {
    void processCommand(GameCommand command);
    void update(long nowMillis);
}
```

Factory của manager nhận roomId và GameState trước khi start; TV3 có thể tạo extension
gắn world riêng của trận. Network không được giữ GameState để sửa trực tiếp.
Trong loop hiện tại, processCommand được gọi lúc lấy command; sau movement mới gọi
extension.update; cuối cùng tạo snapshot.

**Cần chốt chi tiết quan trọng:** đặt bom khi cùng tick có MOVE dùng vị trí trước hay sau
movement? Hiện nếu TV3 xử lý ngay PlantBombCommand trong processCommand thì vị trí được
đọc trước movement của tick. Nếu muốn đặt sau movement, extension cần giữ ý định rồi
xử lý trong update. Nhóm phải chọn một cách và viết test, không suy đoán từ tên hàm.

### 4.2. Timer bom: chọn một trong hai phương án

| Phương án | Cần thay đổi | Nhận xét |
|---|---|---|
| TV3 kiểm tra deadline trong extension.update | TV3 giữ danh sách bom/deadline và xử lý khi đến hạn | Đề xuất đơn giản cho nhịp 20 Hz; không cần thread timer sửa world |
| Scheduler chỉ enqueue command nổ bom | TV2 bổ sung command/API, TV3 nối scheduler và xử lý lệnh | Giữ thiết kế scheduler hiện có nhưng cần chốt khi queue đầy và cleanup |

GameCommand hiện là sealed class, chưa có ExplodeBombCommand; TV3 không thể tự tạo
subclass ngoài danh sách permits. BombScheduler hiện còn gọi bomb.queueExplosion() từ
scheduler thread. Nếu chọn single writer nghiêm ngặt, phải chuyển mutation đó về loop,
không chỉ đưa thao tác explode cuối cùng về loop.

**Không triển khai cả hai cách nổ bom song song.** Nếu giữ scheduler, cần chỉ định ai đóng
task/executor khi room dừng; GameplayExtension hiện chưa có close hook.

### 4.3. Model, world API và luật

| Nội dung | Hiện trạng / điều cần chốt |
|---|---|
| Model chung | TV2 đã thêm Direction/TileType/TilePosition; TV3 còn kiểu riêng, cần chuyển hoặc adapter rõ ràng |
| Pixel/tile | Player dùng pixel, EntityData dùng tile; map lưu theo [y][x] |
| Ô đặt bom | Đề xuất theo tâm hitbox; cùng một quy tắc ở server và prediction |
| Collision với bom | Hiện đang chồng ô bom thì được thoát; ra hết hitbox không vào lại |
| Player-player | Hiện cho đi xuyên nhau; nhóm xác nhận hoặc yêu cầu bổ sung |
| Damage | TV3 quyết định chết/hit; TV2 lưu alive và ngăn movement sau chết |
| Speed item | TV3 thay speed thật trên loop; TV2 snapshot và replay theo speed server |
| Thắng/thua | Chỉ định người kiểm tra số player sống, thời điểm GAME_OVER và dừng loop |

World adapter có luật item tạm trước đây đã xóa. TV3 cần nêu API thực sự cần cho BombWorld/
ItemWorld; TV2 cung cấp API state, không tự nhận lại luật item. Chốt thêm fuse, range,
bomb limit, speed cơ bản/tối đa, tileSize, hitbox và spawn an toàn.

### 4.4. Event và snapshot không được tranh quyền

Đề xuất snapshot/full là nguồn state khôi phục; event dùng cho hiệu ứng tức thời.
Một lần BOMB_EXPLODE hoặc ITEM_PICKUP không được áp dụng nâng chỉ số hai lần vì client
vừa nhận event vừa nhận delta. Chốt thứ tự phát event/snapshot và định danh event nếu
cần bỏ trùng. Full cho người reconnect phải chứa đủ state hiện còn tồn tại; animation
ngắn hạn có cần khôi phục hay không phải quyết định riêng.

## 5. Làm việc với TV4 — protocol, input và delta

### 5.1. MOVE và ACK

**Đã có dưới dạng codec đề xuất:**

```json
{"sequence":1,"direction":"RIGHT","moving":true}
```

Mỗi mẫu ứng với 50 ms; server mô phỏng tối đa một mẫu mới/player/tick. Snapshot có
lastProcessedInput. Client quay về vị trí authoritative, bỏ mẫu đã ACK rồi replay phần còn lại.

**Cần chốt:**

- Có giữ sampled input 20 Hz không? Không trộn với mô hình chỉ gửi nhấn/nhả mà vẫn replay
  như mỗi packet là một bước thời gian. Sequence=0 hiện chỉ để tương thích demo cũ.
- Có gửi mẫu đứng yên liên tục hay chỉ một STOP? Mẫu idle tạo ACK thay đổi, nên vẫn có
  delta; phải tính tác động vào băng thông.
- Rate limit, giới hạn backlog và cách trả lỗi khi submit=false. Không ACK thành công
  chỉ vì decoder đọc được packet; ACK input là mức server đã chấp nhận để mô phỏng.
- Trường hợp command bị bỏ/trùng/không hợp lệ, client giữ hay bỏ lịch sử thế nào?
- Nhịp client nhanh hơn server gây backlog thì xử lý ra sao? Hiện chưa có cơ chế tự cân
  tốc độ lấy mẫu theo backlog/đồng hồ server; cần đo khi chạy lâu và khi mạng trễ.
- Mất focus, client chết hoặc queue gửi đầy phải dừng input và thông báo, không tiếp tục
  prediction vô hạn mà không nhận ACK.

### 5.2. Snapshot và định dạng truyền

**Đã có:** full đầu và mỗi 100 tick, delta khi đổi trạng thái; tick/baseTick; map, player,
entity thêm/đổi và khóa xóa. SnapshotPacketCodec v2 dùng binary bọc Base64 trong JSON.
PacketEncoder chung thêm framing length/type. Đây là đề xuất tương thích lớp mạng hiện có,
chưa phải protocol nhóm đã duyệt. Base64 tăng overhead, không phải thuật toán nén.

TV4 cần quyết định giữ envelope này cho mốc tích hợp đầu hay đổi sang payload thống nhất
khác. Nếu đổi, cùng nâng client/server và benchmark; không chỉ sửa một đầu.
Parser MOVE/envelope hiện chỉ nhận dạng canonical, chưa hỗ trợ JSON tùy thứ tự/khoảng trắng.
Enum hiện mã hóa theo ordinal: không đổi thứ tự tùy ý; cân nhắc mã số cố định trong protocol.

### 5.3. Baseline, resync và client chậm

| Điều phải thống nhất | Đề xuất |
|---|---|
| Người đọc outbound queue | Một worker mỗi runtime đọc rồi fan-out tới client trong room |
| Nhiều người cùng poll | Không dùng: queue là consume, không phải mỗi người tự nhận một bản |
| createUpdate() | Chỉ là update gần nhất, có thể null/delta; không dùng thay full cho client mới |
| Lỗi baseTick | Client dừng ghép delta sai, yêu cầu full; không áp dụng đại vào state hiện tại |
| Resync request | TV4 chốt message/fields, server xác thực room và giới hạn tần suất |
| Client gửi chậm/queue đầy | Chốt disconnect, hoặc bỏ backlog rồi gửi full với barrier đúng thứ tự |
| Commit baseline | Chỉ chứng minh update vào queue server, chưa chứng minh từng client đã nhận |
| Chơi lại/reconnect | Gắn đúng match/input epoch trước khi nhận lại chuỗi delta |

Chưa cần thêm ACK từng snapshot chỉ vì dùng TCP, nhưng phải xử lý việc tầng ứng dụng bỏ
packet hoặc đổi kết nối. Nếu drop một delta đã commit, phải khôi phục; không tiếp tục
phát chuỗi như thể client còn đúng baseline.

### 5.4. Metrics và bằng chứng cải tiến

TV2 cung cấp kịch bản và thuật toán; TV4 thu thập/tổng hợp kết quả. Cùng một timeline world
phải được mã hóa theo cả full mỗi tick và delta + full định kỳ. Tính cả header, envelope,
ACK và full phục hồi trong phương án đang so sánh.

Kịch bản hiện có: 200 tick mô phỏng, full 110076 bytes, delta 16411 bytes, giảm 85,09%.
Đây là số frame ứng dụng trong fixture, chưa phải benchmark mạng thực; fixture chưa tăng
ACK mỗi mẫu idle như client thật. Không dùng 85,09% làm cam kết cho mọi trận.

Đề nghị bổ sung số đo 2/4 client thực: bytes/s gửi và nhận, packet/s, RTT, queue backlog,
command từ chối, CPU/RAM và sai số correction. Nếu dùng Wireshark để đo TCP/IP overhead,
ghi rõ khác biệt với số byte từ PacketEncoder.

## 6. Các thông số cần ghi thành một bảng cấu hình chung

| Thông số | Hiện có / đề xuất | Quyết định cuối của nhóm |
|---|---|---|
| Tick server | 20 Hz, 50 ms | Chưa chốt |
| Input client | Mẫu 20 Hz | Chưa chốt |
| Full định kỳ | Mỗi 100 tick, khoảng 5 giây khi loop đúng nhịp | Chưa chốt |
| Map/tile/hitbox | Map mặc định 17×15; tile 32 px; fixture thường hitbox 24×24 px | Chưa chốt |
| Speed ban đầu/tối đa | Tùy spawn và luật item, chưa có cấu hình gameplay duy nhất | Chưa chốt |
| Player/room | Runtime nhận 1–4; tối thiểu để chơi thật do lobby quyết định | Chưa chốt |
| Interpolation | Client hiện render lùi khoảng 2 tick | Chưa chốt |
| Collision player-player | Hiện đi xuyên nhau | Chưa chốt |
| Bom/fuse/range/item | TV3 đề xuất, cả nhóm xác nhận | Chưa chốt |
| Reconnect timeout / input epoch | Cần thống nhất session và gameplay | Chưa chốt |
| Queue-full / slow client | Chính sách lỗi và resync cần định nghĩa | Chưa chốt |

Các con số hiện có là cấu hình code/fixture, không tự coi là luật nhóm đã duyệt.

## 7. Thứ tự ghép và tiêu chí nghiệm thu

### Mốc 1 — Movement qua server chính

TV1 + TV2 + TV4 ghép startGame, resolver session, router MOVE, worker snapshot và client
listener. Chạy hai client ở cùng phòng, mở input sau full đầu; chặn client điều khiển ID
khác hoặc room khác. Chưa cần bom để xác minh mốc này.

### Mốc 2 — Client movement và mạng trễ

TV2 kiểm tra nhấn/nhả, mất focus, prediction, reconciliation, interpolation và lỗi gửi.
TV4 hỗ trợ tạo độ trễ có kiểm soát và đo. Đề xuất các kịch bản 0/100/200 ms cùng jitter,
nhóm cần ghi rõ đó là RTT hay delay một chiều; đây chưa phải ngưỡng pass đã được duyệt.
Không hứa triệt tiêu mọi correction; đặt mức sai số/độ trễ chấp nhận sau khi có số đo nền.

### Mốc 3 — Gameplay TV3

Đặt bom, ra khỏi ô bom, không vào lại; bom phá block làm collision thay đổi; death chặn
movement; Speed+ cập nhật cả server/prediction. Event và delta không gây hiệu ứng lặp.

### Mốc 4 — Lifecycle và nhiều phòng

Chạy 2–4 client, hai room độc lập, rời phòng, mất kết nối, reconnect, resync, chơi lại và
đóng room. Mỗi lần stop không còn thread/timer của trận; không nhận packet hoặc input
của trận cũ sang trận mới. Kiểm tra client chậm, queue đầy và runtime extension lỗi.

### Bảng nghiệm thu chung

| Case | Người dẫn | Bằng chứng cần lưu |
|---|---|---|
| Hai/bốn client di chuyển qua server chính | TV2 + TV4 | Video và log tick/ACK |
| Collision wall/block/boundary/bomb | TV2 + TV3 | Test + video tình huống |
| Tách biệt hai phòng | TV1 + TV2 | Log room/match và vị trí không đổi ở phòng kia |
| Prediction/replay dưới delay | TV2 + TV4 | Pending input, ACK, correction error và cấu hình delay |
| Disconnect/reconnect | TV1 + TV2 + TV4 | Session state, input epoch, full phục hồi |
| Bomb/item/death đồng bộ | TV3 + TV2 | Event + state khớp trên mọi client |
| Queue-full/slow client | TV4 + TV2 | Counter, lỗi trả về, hành vi phục hồi |
| Stop room không sót thread/timer | TV1 + TV2 + TV3 | Thread/task trước và sau stop |
| Full so với delta | TV2 + TV4 | Kịch bản, CSV và cách tính byte rõ ràng |

Kiểm thử local hiện có chạy bằng `cmd /c scripts\check-tv2.cmd`. Kết quả này chưa thay thế
nghiệm thu GUI nhiều client, mạng trễ và reconnect end-to-end.

## 8. Nội dung TV2 cần gửi trước buổi họp

- Link `TV2_CONTRACT_MIGRATION.md` và tài liệu này.
- API GameRuntimeManager, PlayerSpawn, GameplayExtension và các command.
- Ví dụ MOVE, snapshot v2, quy tắc tick/baseTick/ACK và cách yêu cầu full.
- Kết quả check-tv2 hiện có cùng danh sách chưa nghiệm thu thực tế.
- Những thay đổi cần người khác làm: TV1 lifecycle/start, TV3 world/extension/model,
  TV4 protocol/router/broadcast/metrics; TV2 tiếp tục client movement.

Không cần gửi hoặc sửa file của người khác để coi như họ đã đồng ý. Sau khi chốt,
cập nhật tài liệu contract cùng PR thay đổi API, có reviewer của module bị ảnh hưởng.

## 9. Mẫu biên bản để điền ngay trong cuộc họp

| Quyết định | Giá trị/giải pháp được chọn | Người thực hiện | Reviewer | Mốc/PR | Trạng thái |
|---|---|---|---|---|---|
| Quyền sửa client movement | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| UUID/int/matchId/input epoch | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| Schema MOVE/snapshot/resync | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| Start/stop và worker broadcast | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| World API/timer/cleanup TV3 | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| Game rules/collision/config | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| Slow client/queue-full | Chưa chốt | Chưa phân công | Chưa phân công | Chưa có | Mở |
| Người tích hợp bootstrap | Chưa chốt | Chưa phân công | Cả nhóm | Chưa có | Mở |
| Kịch bản nghiệm thu/đo | Chưa chốt | Chưa phân công | Cả nhóm | Chưa có | Mở |

Đề xuất lịch họp 60–90 phút: 10 phút phạm vi, 15 phút ID/protocol, 15 phút lifecycle,
15 phút gameplay extension, phần còn lại chốt owner và nghiệm thu. Nếu một mục chưa đủ
thông tin, ghi người cần trả lời và mốc phản hồi; không để nó thành quyết định ngầm.

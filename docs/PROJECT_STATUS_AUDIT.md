# Kiểm tra hiện trạng — nhiệm vụ 1

Đây là ảnh chụp hiện trạng trước M0. Kết quả xử lý các API ở nhiệm vụ 2 được theo dõi tại
[M0_INTEGRATION_CONTRACT.md](M0_INTEGRATION_CONTRACT.md); không dùng bảng lịch sử này thay protocol hiện hành.

Ngày kiểm tra: **21/10/2026**. Phạm vi: đối chiếu mã nguồn với
[PROJECT_COMPLETION_PLAN.md](PROJECT_COMPLETION_PLAN.md), build, chạy test hiện có,
sửa lỗi build/test xác định được. Chưa triển khai tính năng mới hoặc tự chốt protocol.

## 1. Kết luận và cách đọc

**Build và các bộ kiểm tra được chạy trong lượt này đã đạt sau sửa lỗi. Chưa có ứng dụng
multiplayer hoàn chỉnh chạy từ ServerApplication/ClientApplication.** Module đã có nhiều
chức năng, nhưng đường tích hợp production vẫn thiếu.

- `[x]` Đã kiểm chứng bằng lệnh/test nêu trong tài liệu.
- `[ ]` Chưa hoàn thành theo kế hoạch hoặc chưa đủ bằng chứng nghiệm thu.
- “Có mã” chỉ xác nhận đã đọc implementation, không đồng nghĩa đã qua mọi kịch bản.
- Kết quả dựa trên **workspace hiện tại có nhiều thay đổi chưa commit**, không phải một
  bản release hoặc chỉ riêng nội dung HEAD. Không reset/revert các thay đổi có sẵn của người dùng.

## 2. Môi trường và kết quả build/test thực chạy

| Hạng mục | Kết quả |
|---|---|
| JDK đang chạy | Java/javac 25.0.1, java.home là D:/JavaJDK |
| Target dự án | Java 17 qua maven.compiler.release; script TV2 nay cũng dùng --release 17 |
| Maven | 3.9.12 tìm được trong cache wrapper của người dùng; mvn chưa nằm trên PATH |
| JAVA_HOME ban đầu | Không hợp lệ với Maven; chỉ đặt D:/JavaJDK trong tiến trình kiểm tra, không đổi cấu hình máy |
| Dependency ban đầu | Cache thiếu POM/dependency Surefire 3.5.2; offline chưa chạy được test |
| Sau bổ sung dependency | Maven tải dependency cần thiết với quyền đã được cho phép; không đổi phiên bản trong pom.xml |
| Build sạch | **mvn clean test: BUILD SUCCESS**, biên dịch 125 file production và 23 file test với release 17 |
| JUnit | **15 test, 0 failure, 0 error, 0 skipped**, thuộc 10 lớp test |
| Script TV2 | 4 chương trình Check đều PASS |
| NetworkTest main | PASS: codec, framing/validation, router, metrics, TCP loopback |
| Demo console | 5 demo core/movement đều PASS |
| Java bytecode TV2 | major version **61**, tương ứng Java 17; trước sửa script là 69, tương ứng Java 25 |

Test thực chạy trên JVM 25 với mã được biên dịch cho Java 17; chưa chạy lại trên JVM 17 thật.
Chưa mở cửa sổ Swing để kiểm thử bàn phím/thao tác người dùng và chưa chạy LAN nhiều máy.

JUnit đã chạy:

| Lớp | Số test | Phần được kiểm chứng |
|---|---:|---|
| SessionManagerTest | 2 | Token sai bị từ chối, reconnect phục hồi trạng thái phiên trong test module |
| RoomManagerTest | 1 | Tạo/join và snapshot danh sách không sửa được cấu trúc |
| BombSystemTest | 4 | Bom trùng ô, phá block/dừng vùng nổ, chain reaction, death event một lần |
| BombSchedulerTest | 1 | Callback đưa bomb ID tới sink trong test scheduler |
| ItemSystemTest | 2 | Spawn/pickup và hiệu ứng theo fake world của test |
| BombPresentationStateTest | 1 | State hiển thị bomb/explosion |
| Java2DRendererTest | 1 | Renderer vẽ vào BufferedImage, không phải kiểm thử thao tác trên cửa sổ thật |
| DeltaCompressionTest | 1 | Full/delta, replica và regression theo workload mô phỏng |
| MovementFeatureTest | 1 | Prediction/reconciliation/interpolation, collision, runtime và harness TCP |
| RuntimeContractTest | 1 | FIFO, đồng thời, queue, lifecycle, cô lập room, snapshot và dọn thread |

Các main chạy riêng:

- ApiMigrationCheck, MovementFeatureCheck, RuntimeContractCheck, DeltaCompressionCheck
  qua `scripts/check-tv2.cmd`.
- NetworkTest; GameCommandQueueDemo; GameStateDemo; GameLoopDemo;
  MovementSystemDemo; StateDeltaBuilderDemo.

Một số Check được gọi lại từ JUnit, nên không cộng chúng thành số test độc lập mới.
`NetworkTest` có tên kết thúc bằng Test nhưng chỉ có main/runAll, không có `@Test`;
không được coi là đã chạy chỉ nhờ `mvn test`.

Kết quả delta trong workload kiểm tra: full 110.076 byte, delta 16.411 byte,
tiết kiệm 85,09%, phát 83/200 update. **Đây là số đo workload codec/state của test,
không phải benchmark gameplay LAN hoặc lưu lượng TCP/IP thực tế.**

### Lệnh và log để đối chiếu

Với môi trường đã cấu hình Maven/JAVA_HOME hợp lệ:

```powershell
mvn clean test
cmd /c scripts\check-tv2.cmd
java -cp "target/classes;target/test-classes" com.bombit.integration.NetworkTest
java -cp "target/classes;target/test-classes" com.bombit.server.game.core.GameCommandQueueDemo
java -cp "target/classes;target/test-classes" com.bombit.server.game.core.GameStateDemo
java -cp "target/classes;target/test-classes" com.bombit.server.game.core.GameLoopDemo
java -cp "target/classes;target/test-classes" com.bombit.server.game.movement.MovementSystemDemo
java -cp "target/classes;target/test-classes" com.bombit.server.game.movement.StateDeltaBuilderDemo
```

Trên máy kiểm tra, lệnh Maven thực dùng executable ở:
`C:/Users/Chinh/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn.cmd`,
với JAVA_HOME=D:/JavaJDK và `-Dmaven.repo.local=C:/Users/Chinh/.m2/repository`.
Đường dẫn này là đặc thù máy hiện tại, không đưa vào cấu hình build chung.

Log cục bộ sau kiểm tra nằm ở `target/audit-maven-clean-test.log`, `target/audit-tv2.log`,
`target/audit-NetworkTest.log`, các `target/audit-*Demo.log` và báo cáo `target/surefire-reports`.
Đây là output sinh tự động, không commit; lần clean tiếp theo có thể xóa chúng.

## 3. Lỗi đã sửa trong phạm vi nhiệm vụ 1

| Mã | File / phụ trách | Nguyên nhân và sửa tối thiểu | Kiểm chứng |
|---|---|---|---|
| FIX-01 | src/test/java/com/bombit/server/game/bomb/BombSystemTest.java — TV3 | Import model.TileType làm FakeBombWorld.tileType trả sai kiểu so với BombWorld hiện tại. Chỉ bỏ import nhầm để dùng bomb.TileType cùng package; giữ nguyên nội dung các test khác | Lỗi được tái hiện bằng javac --release 17; build sạch sau sửa và 4 test bom đạt |
| FIX-02 | scripts/check-tv2.cmd — TV2 | javac không có --release nên trên JDK 25 tạo class Java 25, trái target 17 trong pom. Thêm --release 17 | Script chạy qua; javap xác nhận major version đổi từ 69 sang 61 |

Không chuyển toàn bộ model TV3 trong lượt này: đó là thay đổi contract tích hợp thuộc M0/M3,
không cần thiết để sửa lỗi import. Không xóa API tương thích cũ, đổi schema, thêm handler hay bootstrap.
Các lỗi môi trường cache/PATH/JAVA_HOME được tách khỏi lỗi mã nguồn; không chỉnh pom để né lỗi cache.

## 4. Checklist đối chiếu từng mốc

### M0 — Nền tảng và hợp đồng: chưa đóng

- [x] Build/test workspace hiện tại được kiểm chứng với target Java 17.
- [x] Có tài liệu quyền sở hữu module và kiểm kê từng file.
- [ ] Chọn người tích hợp và chốt quyền sửa bootstrap/lobby; kế hoạch hiện vẫn là đề xuất.
- [ ] Đồng nhất PROTOCOL.md, docs/protocol.md, tài liệu thành viên và codec thực tế.
- [ ] Chốt mapping UUID session ↔ ID int gameplay, matchId/epoch, ACK và reset khi chơi lại/reconnect.
- [ ] Chốt message resync, full đầu, thứ tự start/broadcast, rollback và chính sách client chậm.
- [ ] Chốt model/adapter bom-item, thời gian gameplay và luật thắng/hòa/timeout.

### M1 — Hai client di chuyển thật: chưa đóng

- [x] Module movement/runtime/prediction và harness TCP đạt kiểm tra hiện có.
- [x] Codec/router/ClientNetwork đạt TCP loopback độc lập.
- [ ] ClientHandler production nối PacketDecoder → MessageRouter: hiện chỉ đọc byte/heartbeat.
- [ ] Handshake CONNECT và vào phòng demo được nối tới session thật.
- [ ] MovementHandler được đăng ký và dùng cùng runtime manager của ứng dụng.
- [ ] Có worker đọc pollUpdate theo room, encode và broadcast đúng thứ tự.
- [ ] ServerApplication/ClientApplication khởi tạo ứng dụng thật: hiện chỉ in scaffold.
- [ ] Client nhận localId, đăng ký snapshot listener, nhận full rồi mới bật input.
- [ ] Callback resync và xử lý disconnect được nối trọn đường client/server/game.
- [ ] Nghiệm thu hai cửa sổ độc lập, bốn client và LAN nhiều máy.

Harness MovementFeatureCheck tự tạo socket/session/runtime và gọi handler trong test;
PASS của harness không chứng minh ClientHandler hoặc hai bootstrap production đã được tích hợp.

### M2 — Lobby và vòng đời trận: chưa đóng

- [x] Tạo/join và snapshot danh sách phòng đạt test module hiện có.
- [x] Reconnect token/phục hồi trạng thái session đạt 2 test module hiện có.
- [ ] Nối CREATE/JOIN/LEAVE/READY/START/CHAT qua router và giao diện lobby.
- [ ] Start phòng gắn với start runtime, mapping/spawn và rollback khi lỗi.
- [ ] Nối soft/hard disconnect tới PlayerDisconnectedCommand, stopGame và worker cleanup.
- [ ] Nghiệm thu host rời, room đầy, start đồng thời, hai phòng và chơi lại nhiều lần.

### M3 — Gameplay: chưa đóng

- [x] Unit test hiện có của bom/item/scheduler/presentation/renderer đều đạt.
- [ ] Có adapter GameState triển khai BombWorld/ItemWorld: chưa thấy implementation production.
- [ ] Có GameplayExtension ghép BombSystem/ItemSystem; factory mặc định vẫn dùng none().
- [ ] Có handler/codec packet đặt bom và event được nối với router/broadcast.
- [ ] Map/player/entity có nguồn trạng thái thống nhất, identity/position được chuyển đúng giữa module.
- [ ] Ghép renderer TV3 vào màn hình movement chung; đồng bộ deadline, full/resync và event.
- [ ] Game over, bảng kết quả, luật hòa/timeout và reset để chơi lại được nối end-to-end.

### M4 — Độ ổn định và đo mạng: chưa đóng

- [x] Có kiểm tra queue/FIFO/cleanup runtime, codec/router/metrics và TCP loopback.
- [ ] Có kiểm tra production server cho fragmented/truncated frame, payload lỗi, spam và client chậm.
- [ ] Kiểm chứng writer/disconnect/heartbeat/runtime cleanup cùng một ứng dụng.
- [ ] Nối reconnect từ packet đến world/full/input sequence nếu nhóm chọn tính năng này.
- [ ] Chạy benchmark gameplay 1/2/4 client và LAN, thu CSV/log/capture thực tế.
- [ ] Đo timeout, CPU/RAM và tài nguyên qua nhiều vòng start/stop; chưa có bằng chứng nghiệm thu này.

### M5 — Bàn giao: chưa đóng

- [ ] Script chạy ứng dụng hoàn chỉnh, artifact và cấu hình host/port đã thử trên máy khác.
- [ ] README phản ánh đúng implementation hiện tại, hướng dẫn toàn bộ luồng chơi.
- [ ] Protocol chính thức, báo cáo số liệu thực đo, kịch bản/video demo và hạn chế được bàn giao.

## 5. API mâu thuẫn hoặc hợp đồng chưa khép kín

Đây là danh sách để xử lý ở nhiệm vụ 2. Chưa đổi các API này trong nhiệm vụ 1.

| Mã / ưu tiên | Bằng chứng trong mã/tài liệu | Tác động và phần cần thống nhất | Phụ trách |
|---|---|---|---|
| API-01 / P0 | PROTOCOL.md mô tả UUID cho playerId; Player/GameCommand/snapshot dùng int; PlayerSpawn có sessionId String và playerId int | Chốt mapping và field gửi cho client; không đổi int hoặc String riêng lẻ | TV1 + TV2 + TV4 |
| API-02 / P0 | PROTOCOL.md mô tả MOVE direction/inputSeq; MovePacketCodec dùng sequence:long, direction, moving và chỉ nhận JSON canonical | Client theo tài liệu sẽ bị codec từ chối; chọn schema và quy tắc parse/version | TV2 + TV4 |
| API-03 / P0 | PROTOCOL.md mô tả GAME_SNAPSHOT là object players/bombs/items; SnapshotPacketCodec dùng envelope v2 chứa binary Base64, tick/baseTick/ACK | Định nghĩa một codec/schema chính thức; chốt full/delta/resync | TV2 + TV4 |
| API-04 / P0 | GameStateUpdate/MovePacketCodec.Input chưa mang matchId; replica chỉ so tick; có thể có nhiều trận trong một room | Chốt match epoch trong envelope hoặc cơ chế cô lập tương đương khi chơi lại | TV1 + TV2 + TV4 |
| API-05 / P0 | RoomManager.startGame đổi phòng/session sang PLAYING và phát GAME_STARTED nhưng chưa gọi runtime manager; payload thiếu local numeric mapping/map/time so với tài liệu | Chốt giao dịch start, thứ tự thông báo/full và rollback | TV1 + TV2 + người tích hợp |
| API-06 / P0 | Model Direction/TilePosition/TileType có cả trong game/model và game/bomb; BombWorld/ItemWorld và renderer dùng kiểu bom | Cần adapter hoặc migration đồng bộ; không chỉ thay import ở một test | TV2 + TV3 |
| API-07 / P0 | RoomCleanupSink soft disconnect/reconnect còn callback rỗng; hard drop dọn room/connection nhưng chưa gọi runtime | Session test đạt chưa đồng nghĩa gameplay dừng/khôi phục/đóng đúng | TV1 + TV2 |
| API-08 / P0 | MovementGameWindow nhận Runnable resync; PacketType có FULL_STATE_SYNC S→C nhưng chưa có đường yêu cầu full được nối trong production | Chốt request resync, quyền truy cập, rate limit và consumer queue | TV2 + TV4 |
| API-09 / P1 | Bomb.id và EntityData.id là int; PROTOCOL.md ghi bombId long, owner UUID trong khi gameplay nội bộ player int | Chốt wire range/identity conversion và nơi cấp ID, tránh overflow hoặc gán nhầm chủ | TV2 + TV3 + TV4 |
| API-10 / P1 | docs/protocol.md còn DRAFT, PROTOCOL.md chi tiết hơn; tài liệu dùng BOMB_PLANTED, class event là BombPlacedEvent | Chọn nguồn tài liệu chính thức và mapping event → packet; không suy tên packet từ tên lớp | TV3 + TV4 |
| API-11 / P1 | SessionManager.reconnect cần playerId/token/socket; PROTOCOL.md RECONNECT chỉ có playerId; CONNECT_ACK chưa ghi token | Schema reconnect chưa đủ dùng API xác thực hiện có; chốt trước khi bật reconnect | TV1 + TV4 |
| API-12 / P1 | READY tài liệu nhận ready:boolean; RoomManager.toggleReady đổi trạng thái mỗi lần gọi | Chốt semantics set/toggle, nhất là gửi lặp request | TV1 + TV4 |
| API-13 / P1 | ROOM_STATE trong code có roomName/hostId/state nhưng tài liệu ghi maxPlayers/roomStatus; PLAYER_DISCONNECTED code chỉ có playerId, tài liệu có reason | Cập nhật schema và caller cùng lúc, thống nhất field bắt buộc/tùy chọn | TV1 + TV4 |
| API-14 / P1 | SessionWriter tự ghi frame OutboundMessage; PacketEncoder cũng sở hữu framing/validation ở protocol | Hai nơi cùng tạo frame; cần chốt adapter/gửi packet, giới hạn và hành vi queue đầy | TV1 + TV4 |
| API-15 / P1 | GameplayExtension có processCommand/update, chưa có close; BombScheduler.close chỉ hủy task, executor được truyền từ ngoài | Chốt ai sở hữu và đóng scheduler/executor khi stopGame/runtime failure | TV2 + TV3 + người tích hợp |

## 6. Khoảng trống kiểm thử và rủi ro cần xử lý tiếp

- Test room hiện chỉ một kịch bản tạo/join; chưa chứng minh ready/start/host transfer/room full
  và rollback hoạt động qua mạng. Hai test session chưa bao phủ timeout/reconnect đồng thời.
- Test bom/item dùng fake world, chưa kiểm tra adapter với GameState/runtime; renderer test là offscreen.
- NetworkTest là main riêng; chưa được Maven tự chạy như JUnit. ApiMigrationCheck nằm trong script TV2.
- ClientNetwork có queue pull 1.024 packet đồng thời vẫn gọi listener. Client chỉ dùng listener có thể
  làm queue pull đầy và ghi log; cần chốt sử dụng/overflow, không coi là packet listener đã bị mất.
- SessionWriter.close hiện interrupt worker, không join; write có thể đang block và IOException
  chỉ đặt running=false. Cần test shutdown/socket close và đường báo lỗi trong ứng dụng tích hợp.
- LatencyMonitor có pending ping; chưa thấy chính sách expire/timeout và giới hạn pending.
  Benchmark hiện đếm kết nối/gửi PING, chưa chờ PONG hay đo gameplay; không dùng nó làm bằng chứng RTT.
- Spawn runtime kiểm tra va chạm map và trùng ID; chưa thấy kiểm tra hitbox giữa các player spawn.
  Cần quyết định luật overlap rồi mới thêm validation/test tương ứng.

Các điểm trên là quan sát mã nguồn/phạm vi còn thiếu, không phải tất cả đều là lỗi đã tái hiện.
Không mở rộng sửa gameplay/concurrency khi chưa có contract và bài kiểm tra xác định hành vi mong muốn.

## 7. Việc kế tiếp và file thay đổi trong lượt này

Ưu tiên nhiệm vụ 2: giải quyết API-01 đến API-08, chỉ định người tích hợp, chọn nguồn protocol,
chốt M1 trước khi thêm handler/bootstrap mới. Sau đó thực hiện đường truyền server và demo movement.

| File thay đổi trong nhiệm vụ 1 | Phụ trách | Vai trò |
|---|---|---|
| src/test/java/com/bombit/server/game/bomb/BombSystemTest.java | TV3 | Sửa đúng import gây lỗi build |
| scripts/check-tv2.cmd | TV2 | Khóa target Java 17 |
| docs/PROJECT_STATUS_AUDIT.md | Chung | Báo cáo/checklist kiểm tra hiện trạng |
| docs/PROJECT_COMPLETION_PLAN.md | Chung | Liên kết tới kết quả kiểm tra nhiệm vụ 1 |
| docs/FILE_OWNERSHIP_NOTES.md | Chung | Thêm file báo cáo và nhật ký các thay đổi lượt này |

Những file khác đã thay đổi/untracked trước lượt này được giữ nguyên, không quy nhận là thay đổi
do nhiệm vụ 1. Không tự commit, không chạy git reset/checkout và không sửa feature ngoài phạm vi.

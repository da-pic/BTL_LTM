# Nhiệm vụ 6 — Tích hợp luật bom phía server

Đây là báo cáo lịch sử nhiệm vụ 6. Nhiệm vụ 7 đã nối bootstrap lobby mặc định,
packet/phím và renderer; xem [TASK7_CLIENT_BOMBS.md](TASK7_CLIENT_BOMBS.md) cho hiện trạng mới.

Ngày xác minh: 22/10/2026. Hoàn thành phạm vi server qua runtime và kiểm thử
TCP loopback. Đây là một phần M3, chưa phải nghiệm thu gameplay hoàn chỉnh.

## Luồng tích hợp đã kiểm chứng

`GameRuntimeManager.ExtensionFactory` nhận `roomId`, `GameState` và bảng
`sessionId -> playerId` để tạo một `BombGameplayExtension` riêng cho từng trận.
Factory này tạo `BombWorldAdapter` và `BombSystem`; network chỉ submit
`PlantBombCommand(playerId)` vào runtime. Không trả mutable world cho network.

`GameLoop` gọi `onStart`, xử lý command FIFO, cập nhật movement, gọi
`extension.update`, rồi tạo full/delta snapshot. `onStop` luôn chạy trong
`finally`, trên cùng game thread, kể cả khi callback event/kết quả gây lỗi.
Khởi tạo map/player trước khi loop bắt đầu; mọi thay đổi world do bom khi trận
đang chạy và khi cleanup đều thực hiện trên game thread.

- `BombRules(fuseMillis, range, quota)` là cấu hình bắt buộc cho từng trận.
  Chưa chọn mặc định cân bằng B4. Các giá trị trong test chỉ là fixture.
- `BombWorldAdapter` dùng `game.model.TilePosition/TileType`; hệ thống bom dùng
  `game.model.Direction`. Kiểu legacy trong package bom không dùng trên đường tích hợp này.
- Đặt tại tile chứa tâm hitbox server. Từ chối player không tồn tại, chết,
  disconnect, trận không RUNNING, ô không trống, ô có bom hoặc hết quota.
- Deadline dùng hiệu `System.nanoTime()` từ lúc đặt, kiểm tra tại tick đầu tiên
  đủ fuse. Timestamp đặt/hết hạn/event gửi ra ngoài vẫn dùng giờ server.
  Đổi đồng hồ hệ thống không kích nổ sớm hoặc làm chậm fuse mô phỏng.
- Không dùng `BombScheduler` trong runtime này. Lớp scheduler legacy vẫn tồn tại
  để tương thích, không phải đường thực thi đã tích hợp.
- Bom hết hạn và bom bị chain reaction được xử lý chung một batch; mỗi bom nổ
  tối đa một lần. Tường cứng và biên map chặn tia. Block mềm bị phá nhưng vẫn
  chặn mọi tia khác trong cùng batch, kể cả nhiều bom cùng deadline.
- Bom được chiếu thành `EntityData.Kind.BOMB` trong `GameState` để movement và
  snapshot dùng cùng world. Bom chặn người bên ngoài; mọi người đang chồng ô
  bom được đi ra, sau khi hết chồng hitbox thì không được vào lại.
- Nổ xóa entity và giải phóng quota. Map bị phá và player alive được đưa vào
  snapshot. Damage hiện xử lý tức thời theo hitbox giao vùng nổ; chỉ chạm mép
  không gây damage. Luật thắng/thua, thời gian tồn tại lửa và item thuộc bước sau.
- Stop/failure hủy các bom còn lại và xóa projection. Chơi lại tạo adapter,
  map, matchId mới; ID bom bắt đầu lại trong phạm vi match mới.

## File triển khai và phạm vi thay đổi

Mã tích hợp dưới đây đã có trong workspace từ lượt trước, được rà soát và
chạy lại kiểm thử ở lượt tiếp tục này:

| Nhóm | File chính | Vai trò |
|---|---|---|
| Luật bom | `server/game/bomb/BombRules.java`, `BombCommandResult.java`, `BombError.java`, `BombGameplayExtension.java` | Cấu hình, kết quả command và extension |
| Nổ | `server/game/bomb/BombSystem.java`, `ExplosionResult.java`; `server/game/event/BombExplodedEvent.java` | Batch chain reaction, barrier của block, kết quả/event bất biến |
| Runtime | `server/game/runtime/BombWorldAdapter.java`, `GameplayExtension.java`, `GameRuntimeManager.java`; `server/game/core/GameLoop.java` | Adapter, factory theo trận, lifecycle trên game thread |
| Model/collision dùng chung | `server/game/model/`; `server/game/movement/MovementSystem.java`, `CollisionSystem.java` | Model tile/direction chung, va chạm entity bom |
| Test | `server/game/bomb/BombGameplayTest.java`, `server/game/runtime/BombRuntimeIntegrationTest.java` trong `src/test/java/com/bombit/` | 9 test luật/adapter và 4 test runtime/TCP |

Các đường dẫn production trong bảng tương đối với `src/main/java/com/bombit/`.
Lượt tiếp tục này cập nhật `docs/TASK6_SERVER_BOMBS.md`,
`docs/PROJECT_COMPLETION_PLAN.md` và `README.md`; không cần sửa thêm mã Java
sau khi rà soát và chạy hồi quy. Giữ nguyên các thay đổi hiện có của người dùng.

## Checklist nghiệm thu nhiệm vụ 6

- [x] BombSystem chạy qua GameplayExtension và world adapter dùng model chung.
- [x] Đặt bom: tâm hitbox, alive, trạng thái trận/kết nối, ô hợp lệ, trùng ô, quota.
- [x] Fuse đúng biên deadline, không nổ sớm, không phụ thuộc thay đổi giờ hệ thống.
- [x] Chain reaction và nhiều root cùng tick: nổ một lần, block chắn suốt batch.
- [x] Phá block, tường cứng và biên map; death event không lặp.
- [x] Collision với bom, thoát ô đặt và cấm quay lại; bỏ vật cản sau nổ.
- [x] Adapter từ chối đọc/ghi ngoài game thread; callback runtime cùng game thread.
- [x] Full/delta phản ánh bom thêm/xóa, tile bị phá và alive.
- [x] Hai phòng độc lập, chơi lại không giữ bom cũ; cleanup stop/callback failure.
- [x] Hai replica TCP nhận cùng thay đổi bom/map/alive; full resync sau nổ.
- [x] Hồi quy movement M1, lobby M2, transport và build Java 17.
- [ ] Packet PLANT_BOMB, kiểm tra session/matchId/rate limit cho lệnh đặt bom: nhiệm vụ 7.
- [ ] Nối phím đặt bom, renderer/event hiệu ứng, chống event trùng và dựng hiệu ứng sau resync: nhiệm vụ 7.
- [ ] Item, kết quả trận, luật thắng/hòa/timeout và cân bằng cuối cùng: nhiệm vụ 8.
- [ ] Nghiệm thu Swing trực tiếp, nhiều máy LAN và stress dài hạn.

## Kiểm thử thực chạy

JDK 25.0.1, biên dịch target Java 17; Maven 3.9.12 offline với dependency đã cache.
Lệnh chuẩn tại thư mục project:

```powershell
mvn test
mvn "-Dtest=BombGameplayTest,BombRuntimeIntegrationTest" test
cmd /c scripts\build-demo.cmd
cmd /c scripts\check-tv2.cmd
java -cp "target/classes;target/test-classes" com.bombit.integration.NetworkTest
```

Lệnh focused được cung cấp để tái chạy; lượt này hai lớp bom được thực chạy
trong toàn bộ suite. Maven trên máy kiểm tra dùng đường dẫn executable/cache
đã ghi tại `PROJECT_STATUS_AUDIT.md`, với `JAVA_HOME=D:/JavaJDK` trong tiến trình.

| Kiểm tra | Kết quả thực chạy 22/10/2026 |
|---|---|
| Maven toàn bộ, ngoài sandbox | 69 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS |
| Hai lớp tích hợp bom trong suite | 13 test đạt: 9 luật/adapter, 4 runtime/TCP |
| `build-demo.cmd` | Exit 0, biên dịch production thành công |
| `check-tv2.cmd` | Exit 0; API, movement, runtime, delta checks đạt |
| `NetworkTest` main riêng | PASS codec, framing, validation, router, metrics, TCP loopback |

Lần chạy suite trong sandbox có 68 test đạt và một lỗi
`MovementDemoProcessTest`: cả hai client hết hạn chờ full snapshot trong smoke
4 giây. Chạy riêng test này ngoài sandbox đạt; chạy lại toàn bộ suite ngoài
sandbox cũng đạt. Kết quả phù hợp với giới hạn môi trường TCP đa tiến trình;
chưa kết luận được cơ chế hạn chế cụ thể. Không tăng timeout hoặc sửa gameplay
để che lỗi môi trường.

Log sinh tự động, không commit: `target/task6-tests.log` (lần lỗi trong sandbox),
`target/task6-m1-recheck.log`, `target/task6-m1-unsandboxed.log`,
`target/task6-tests-unsandboxed.log`, `target/task6-build.log`,
`target/task6-tv2.log`, `target/task6-network.log` và `target/surefire-reports/`.

## Giới hạn kiểm chứng và bước tiếp theo

Test TCP chủ động submit `PlantBombCommand` ở phía server; không phải client
gửi packet đặt bom. `DemoGameServer` và cấu hình mặc định `LobbyGameServer`
vẫn dùng `GameplayExtension.none()` để giữ demo movement M1/M2. Muốn tạo
runtime có bom, truyền `ExtensionFactory` tạo `BombGameplayExtension` như
fixture `BombRuntimeIntegrationTest`; factory nhận đủ map session/player.

Chưa kiểm chứng thao tác đặt bom trên GUI hoặc mạng nhiều máy. Bước 7 cần
nối cấu hình extension vào bootstrap gameplay, handler PLANT_BOMB chính thức,
phím đặt bom và presentation/event, rồi kiểm thử hai client end-to-end.
Không đánh dấu M3 hoàn thành chỉ dựa trên kết quả nhiệm vụ 6.

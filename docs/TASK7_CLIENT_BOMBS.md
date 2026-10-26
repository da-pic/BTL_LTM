# Nhiệm vụ 7 — Đồng bộ và hiển thị bom trên client

Đây là báo cáo lịch sử nhiệm vụ 7. [Nhiệm vụ 8](TASK8_ITEMS_RESULTS.md) đã nối item/kết quả,
snapshot v4 và thay DTO nội bộ BombNotice bằng GameNotice để dùng chung eventId cho bom/item/death.

Ngày kiểm chứng: 22/10/2026. Đã nối lệnh đặt bom, snapshot/event chính thức và
renderer TV3 vào đường ứng dụng multiplayer. Test TCP và render offscreen đạt;
nghiệm thu thao tác Swing trực tiếp và nhiều máy LAN vẫn còn mở.

## Chạy và demo

Rebuild cả server/client với JDK 25+ từ thư mục project:

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\run-server.cmd 8888
```

Mở hai terminal client riêng:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Chinh
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Mai
```

Connect → Create/Join → khách Ready → host Start. Dùng mũi tên di chuyển và
**Space** đặt bom; giữ Space không tự đặt liên tục. Để thử phá block, đi gần
block tại tile (5,5), đặt bom trong tầm 2 ô, rồi rời đường nổ trước 3 giây.
Hai cửa sổ cùng nhận bom, vùng nổ và map bị phá do server quyết định.
Người đang chồng ô bom được thoát ra, sau đó không thể quay lại qua bom.
Mất focus xóa trạng thái giữ phím; chết thì dừng input và không đặt thêm bom.

Cấu hình demo mặc định: fuse 3000 ms, range 2, quota 1. Đây là cấu hình demo
có thể đổi, chưa phải quyết định cân bằng cuối cùng B4. Ví dụ chạy trực tiếp:

```powershell
java -Dbombit.bomb.fuseMillis=2000 -Dbombit.bomb.range=3 -Dbombit.bomb.quota=2 -cp target/demo-classes com.bombit.server.app.ServerApplication 8888
```

Lửa hiển thị 600 ms, không gây damage kéo dài: damage vẫn tính một lần tại tick
nổ trên server như nhiệm vụ 6. Chưa có kết quả thắng/hòa tự động; host dùng
**End round** ở lobby để kết thúc/chơi lại. Leave/disconnect vẫn hủy lượt theo M2.
Profile M1 (`run-server.cmd 8888 2`, client `--demo` hoặc `--headless`) vẫn chỉ movement.

## Hợp đồng và cách chống tác động hai lần

- `PLANT_BOMB {requestId}` có match envelope. Handler lấy playerId từ session,
  không nhận playerId/toạ độ client. ID tăng theo player/match; cũ/trùng bị bỏ
  trước enqueue, kể cả sau khi bom trước đã nổ và quota đã trống. Queue đầy
  không tiêu thụ requestId, cho phép gửi lại. Giới hạn 5 request mới/giây/session/match.
- Server gửi snapshot trước event cùng tick qua writer queue hiện có.
  Event giữ schema chính thức BOMB_PLANTED/BOMB_EXPLODE, gồm eventId tăng theo
  match, timestamp chính xác từ event game thread và ownerPlayerId số từ mapping.
  Game thread giữ notice đến khi snapshot enqueue thành công; worker không đọc
  mutable world. Backlog notice hữu hạn 4096; vượt ngưỡng làm runtime fail/cleanup.
- Snapshot v3 nối bomb frame sau body v2: giờ server và toàn bộ vùng nổ còn
  hiển thị. Bom vẫn dùng EntityData, map/alive vẫn dùng dữ liệu authoritative.
  Snapshot v2/golden bytes cũ không đổi; client mới nhận cả v2/v3.
- Chỉ snapshot đã apply thành công mới thay scene. Tick cũ/trùng bỏ qua;
  delta sai baseline giữ scene cũ và xin full. Full thay toàn bộ bom/vùng nổ,
  kể cả khi client không nhận được event trước đó. Expiry của hiệu ứng cũng
  tạo update dù không có thay đổi map/player/entity.
- Event chỉ thông báo: client xác thực match/schema, dedupe eventId cùng tick
  replica, bỏ event cũ/tương lai. Event không sửa map/alive, không tự thêm/xóa
  bom và không reset animation. Vì vậy nhận event trước snapshot hoặc nhận
  event lặp sau resync không áp dụng damage/phá block/hiệu ứng lần hai.
- Countdown/animation dùng thời gian còn lại so với serverTimeMillis rồi chạy
  theo nanoTime local. Countdown hết không tự xóa bom. Chưa bù latency một chiều;
  hiệu ứng có thể lệch thời gian hiển thị giữa máy theo độ trễ, nhưng cùng vùng nổ/map.

Đặc tả byte và schema: [protocol.md](protocol.md), mục 7–8 và 10.

## File đã sửa/thêm trong nhiệm vụ 7

Đường dẫn production tương đối với `src/main/java/com/bombit/`:

| Nhóm | File |
|---|---|
| DTO/codec mới | `common/dto/BombFrame.java`, `BombNotice.java`; `common/protocol/BombPacketCodec.java` |
| World/lifecycle | `server/game/core/GameState.java`, `GameLoop.java`; `server/game/bomb/BombGameplayExtension.java` |
| Snapshot | `server/game/movement/GameStateUpdate.java`, `StateDeltaBuilder.java`, `SnapshotPacketCodec.java` |
| Bootstrap/handler/event pump | `server/app/LobbyGameServer.java` |
| Replica/controller/connection | `client/game/movement/ClientWorldState.java`, `MovementClientController.java`; `client/app/GameClientConnection.java` |
| Scene/renderer/input | `client/game/bomb/BombScene.java`, `BombPresentationState.java`; `client/game/movement/MovementGameWindow.java` |

Renderer TV3 `BombRenderer`/`ExplosionRenderer` được tái sử dụng trực tiếp,
không thay bằng renderer mới. Các test thêm/sửa dưới `src/test/java/com/bombit/`:

- `common/protocol/BombProtocolTest.java` — 4 test schema, snapshot v2/v3,
  validation, expiry và snapshot retry không mất notice.
- `client/game/bomb/BombSceneTest.java` — 2 test controller/scene: duplicate,
  sai thứ tự/match, full recovery, không gây damage, countdown và render offscreen TV3.
- `integration/BombClientIntegrationTest.java` — 2 test TCP với hai client ứng dụng:
  command thật, cùng bom/vùng nổ/map, resync, replay request, rate limit,
  chống giả playerId/match; kiểm tra riêng bootstrap lobby mặc định có bom.
- `server/game/runtime/BombRuntimeIntegrationTest.java` — sửa test hai replica
  sang PLANT_BOMB qua TCP, kiểm tra event hai phía và vùng nổ sau full resync.

Tài liệu cập nhật: `README.md`, `PROTOCOL.md`, `docs/protocol.md`,
`docs/PROJECT_COMPLETION_PLAN.md`, báo cáo này và ghi chú chuyển tiếp trong
`docs/TASK6_SERVER_BOMBS.md`, `docs/M2_LOBBY_LIFECYCLE.md`.

## Kết quả thực chạy

JDK 25.0.1, compile target Java 17, Maven 3.9.12 offline dùng cache đã có.
Maven chạy ngoài sandbox vì compiler trong sandbox không đọc được một JAR JUnit;
smoke TCP đa tiến trình M1 cũng đã có giới hạn môi trường ở lượt nhiệm vụ 6.

| Kiểm tra | Kết quả |
|---|---|
| `mvn test` toàn bộ sau thay đổi cuối | **77 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS** |
| Test mới trong suite | 8 test mới ở ba lớp protocol/scene/client; test bom/runtime cũ tiếp tục đạt |
| `scripts/build-demo.cmd` | Exit 0, production build đạt |
| `scripts/check-tv2.cmd` | Exit 0, API/movement/runtime/delta checks đạt |
| NetworkTest main riêng | PASS codec, framing, validation, router, metrics, TCP loopback |

Log cục bộ, không commit: `target/task7-tests.log`, `target/task7-focused.log`,
`target/task7-build.log`, `target/task7-tv2.log`, `target/task7-network.log`
và `target/surefire-reports/`. Test mới chỉ nằm trong suite cuối; focused log
ghi lượt chạy trước khi bổ sung test bootstrap mặc định và expiry/retry cuối cùng.

## Checklist và phần chưa kiểm chứng

- [x] PLANT_BOMB chính thức qua session/match, queue game thread, chống gửi lặp/rate limit.
- [x] BOMB_PLANTED/BOMB_EXPLODE đúng schema, eventId và thứ tự snapshot → event.
- [x] Snapshot v3/full resync chứa bom, vùng nổ và map authoritative; v2/M1 giữ tương thích.
- [x] Renderer TV3 và Space được nối vào màn hình gameplay chung; prediction dùng entity collision.
- [x] Hai client ứng dụng qua TCP nhận cùng bom, cells vùng nổ và block bị phá.
- [x] Event trùng/cũ/tương lai/sai match, snapshot trùng/gap và full recovery không gây tác động hai lần.
- [x] Default lobby bootstrap có bom; cleanup runtime/worker và hồi quy M1/M2 đạt.
- [x] Render offscreen bom/lửa bằng renderer TV3.
- [ ] Nghiệm thu Swing trực tiếp: focus, Space khi giữ/nhả, đổi cửa sổ, chết/đóng/mở/chơi lại.
- [ ] Hai hoặc bốn client trên nhiều máy LAN, jitter/latency và mất mạng thực tế.
- [ ] Stress backlog/slow consumer/flood dài hạn; việc có giới hạn queue/rate không thay cho stress test.
- [ ] Item, bảng chỉ số, kết quả thắng/hòa/timeout tự động: nhiệm vụ 8.

Bước tiếp theo là nhiệm vụ 8; không đánh dấu toàn bộ M3 hoàn tất trước khi
item, điều kiện kết thúc và bảng kết quả được kiểm thử end-to-end.

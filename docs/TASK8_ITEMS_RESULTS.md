# Nhiệm vụ 8 — Item, chết và kết quả trận

Đây là báo cáo nhiệm vụ 8. [Nhiệm vụ 9](TASK9_STABILITY.md) đã bổ sung deadline,
queue/rate và kiểm thử lỗi/stress; đánh giá reconnect được tách riêng, chưa bật trong ứng dụng.

Ngày kiểm chứng: 22/10/2026. Đã nối gameplay server, snapshot/event chính thức,
renderer item, HUD và bảng kết quả vào ứng dụng. Kiểm thử server/TCP và Swing
offscreen đạt; thao tác trực tiếp nhiều cửa sổ, LAN và stress dài hạn chưa nghiệm thu.

## Luật đang chạy

Các giá trị dưới là cấu hình demo có thể đổi, chưa phải xác nhận rubric/cân bằng cuối cùng:

| Nội dung | Hành vi server |
|---|---|
| Drop | Block mềm bị phá có 30% rơi item; BOMB_UP/FIRE_UP/SPEED_UP chia đều |
| BOMB_UP | Quota +1, tối đa 5; ban đầu theo BombRules, mặc định 1 |
| FIRE_UP | Range +1, tối đa 8; ban đầu theo BombRules, mặc định 2; bom đã đặt giữ range cũ |
| SPEED_UP | Speed +25 pixel/s, tối đa 300; ban đầu 150 |
| Pickup | Tự động theo tile chứa tâm hitbox, chỉ người sống; tranh trong cùng tick ưu tiên playerId số nhỏ hơn |
| Damage | Bom gây damage tức thời theo hitbox giao vùng nổ, kể cả chủ bom; chỉ chạm mép không trúng |
| Vòng xử lý tick | Cả batch bom/damage → xóa item cũ trúng blast → drop từ block → pickup → xét kết quả |
| Thắng | Còn đúng một người sống sau cả batch: LAST_ALIVE |
| Hòa | Không còn ai sống: ALL_DEAD; hết 180 giây mà còn >=2 người: TIMEOUT |
| Xếp hạng | Người sống lâu hơn xếp trên; chết cùng tick/đang sống lúc timeout đồng hạng; rank = 1 + số người sống lâu hơn |
| Disconnect/Leave/End | Giữ chính sách M2: ABORTED, không tính thắng/thua, rankings rỗng; kết quả đã công bố được giữ nguyên |

Alive được xét trước timeout nếu cùng tick. Lửa hiển thị 600 ms vẫn chỉ là hiệu
ứng; không gây damage kéo dài. Drop mới được sinh sau toàn bộ batch nên không
bị các tia khác trong batch đó xóa lại. Item không tự hết hạn; khi đã có item
trên ô thì không spawn trùng. Pickup tiêu thụ item một lần ngay cả khi chỉ số
đã chạm trần. Chưa có reconnect/giữ ghế hoặc quy tắc disconnect xử thua cá nhân.

## Kiến trúc đã tích hợp

`MatchGameplayExtension` bọc `BombGameplayExtension` và gọi `ItemSystem` qua
`ItemWorldAdapter` trên cùng game thread. Player giữ quota/range/speed thật;
`BombWorldAdapter` đọc chỉ số hiện tại khi đặt bom. Không có item/player model
riêng trên network thread và không có timer sửa world từ thread khác.

Item là EntityData.Kind.ITEM trong cùng GameState với bom; block/map, alive và
speed vẫn đi qua snapshot chính thức. Snapshot **v4** nối sau body v3: thời gian
còn lại, toàn bộ quota/range player và kết quả cuối nếu đã có. Client nhận cả
v2/v3/v4, giữ golden v2 và profile M1 movement. Rebuild cả server và client.

GameNotice thay DTO nội bộ BombNotice để bom/item/death dùng một eventId tăng
theo match. GameplayPacketCodec gửi ITEM_SPAWN, ITEM_PICKED_UP, PLAYER_DIE đúng
schema đã có; các event không tự cộng chỉ số hoặc gây damage trên client.
Nhận event trùng/trước snapshot hoặc full resync không áp dụng item lần hai.

Khi kết thúc, game thread đóng băng kết quả sau cả batch, publish **full snapshot
cuối**, rồi worker gửi event và GAME_OVER cùng writer queue. Worker dọn runtime,
bom/item, reset ready và đưa phòng về WAITING. Client áp dụng snapshot cuối trên
UI owner trước khi đóng controller, hiển thị tab **Kết quả** từ GAME_OVER của
server. Không suy ra winner từ replica client. GAME_OVER cũ/trùng bị bỏ theo
match/context; trận mới xóa lastResult, controller và dữ liệu simulation cũ.

## Chạy và demo

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\run-server.cmd 8888
```

Mở hai terminal khác:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Chinh
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Mai
```

Connect → Create/Join → khách Ready → host Start. Mũi tên để di chuyển, Space
đặt bom. Đi gần block (5,5), đặt bom trong tầm rồi tránh tia nổ; bước vào ô item
nếu có drop. HUD hiện thời gian/quota/range/speed. Người cuối sống thắng, hoặc
cùng chết/hết giờ hòa; cửa sổ gameplay đóng và lobby chọn tab Kết quả. Ready/Start
để chơi lại. End round/Leave/mất kết nối hủy lượt theo chính sách ở bảng trên.

Để dễ kiểm tra item và timeout, có thể chạy server demo với drop 100%, trận 30 giây:

```powershell
java -Dbombit.item.dropChance=1 -Dbombit.match.durationMillis=30000 -cp target/demo-classes com.bombit.server.app.ServerApplication 8888
```

Các property khác: `bombit.item.maxBombs`, `bombit.item.maxRange`,
`bombit.item.maxSpeed`; bước tăng speed hiện là 25. Fuse/quota/range ban đầu vẫn
dùng các property nhiệm vụ 7. Trần phải không nhỏ hơn chỉ số khởi tạo. Test inject
ItemSpawnPolicy/RNG và nano clock để kiểm tra quyết định mà không phụ thuộc may rủi
hay phải chờ 180 giây. Không dùng các test này làm số đo latency LAN.

## File đã thêm/sửa

Đường dẫn production tương đối với `src/main/java/com/bombit/`:

| Nhóm | File |
|---|---|
| DTO/codec | `common/dto/MatchFrame.java`, `MatchResult.java`, `GameNotice.java` (thay `BombNotice.java`); `common/protocol/GameplayPacketCodec.java`, `BombPacketCodec.java` |
| Luật/item adapter | `server/game/runtime/MatchRules.java`, `MatchGameplayExtension.java`, `ItemWorldAdapter.java`, `BombWorldAdapter.java`; `server/game/item/RandomItemSpawnPolicy.java` |
| World/bom/lifecycle | `server/game/core/Player.java`, `GameState.java`, `GameLoop.java`; `server/game/bomb/BombGameplayExtension.java` |
| Snapshot | `server/game/movement/GameStateUpdate.java`, `StateDeltaBuilder.java`, `SnapshotPacketCodec.java` |
| Bootstrap/kết thúc | `server/app/LobbyGameServer.java` |
| Client | `client/game/movement/ClientWorldState.java`, `MovementClientController.java`, `MovementGameWindow.java`; `client/app/DemoClientConnection.java`; `client/lobby/LobbyWindow.java`, `MatchResultsPanel.java` |

Tái sử dụng ItemSystem, ItemRenderer, BombSystem và renderer TV3; không viết lại
các module này. RandomItemSpawnPolicy được sửa để từ chối xác suất NaN/vô cực.

Test mới, tương đối với `src/test/java/com/bombit/`:

- `server/game/runtime/MatchGameplayTest.java`: 5 test spawn/pickup tranh item,
  quota được bom sử dụng, cả batch cùng chết, thứ hạng, timeout, caps và thread guard.
- `common/protocol/GameplayProtocolTest.java`: 2 test snapshot v4/result và schema item/death/mapping.
- `client/game/item/ItemClientStateTest.java`: 1 test event pickup/death trùng/sai thứ tự
  và full resync không cộng chỉ số/xóa item/giết player lần hai.
- `client/lobby/MatchResultsPanelTest.java`: 1 test bảng winner/draw/abort/reset và render offscreen.
- `integration/MatchLifecycleIntegrationTest.java`: 3 test TCP item/chỉ số/winner,
  cùng chết, disconnect ABORTED, final snapshot trước GAME_OVER và replay không giữ state.

Sửa test cũ `common/protocol/BombProtocolTest.java` để dùng tên GameNotice và
`integration/BombClientIntegrationTest.java` để tái sử dụng helper hai client ứng dụng.
Tài liệu cập nhật: README, PROTOCOL, docs/protocol, PROJECT_COMPLETION_PLAN,
báo cáo này và ghi chú chuyển tiếp trong TASK7_CLIENT_BOMBS.

## Test thực chạy

Ngày 22/10/2026, JDK 25.0.1 compile target Java 17, Maven 3.9.12 offline với cache có sẵn.
Maven chạy ngoài sandbox theo giới hạn đọc JAR và TCP đa tiến trình đã xác minh ở các lượt trước.

| Kiểm tra | Kết quả |
|---|---|
| `mvn test` toàn bộ sau thay đổi | **89 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS** |
| Test mới trong suite | **12 test**, gồm 3 test TCP lifecycle với hai client |
| Bộ tập trung nhiệm vụ 8 | 11 test đạt trước khi thêm test client chống pickup/death trùng cuối cùng |
| `scripts/build-demo.cmd` | Exit 0, production build đạt |
| `scripts/check-tv2.cmd` | Exit 0, API/movement/runtime/delta checks đạt |
| NetworkTest main riêng | PASS codec, framing, validation, router, metrics, TCP loopback |

Log cục bộ: `target/task8-tests.log`, `target/task8-focused.log`,
`target/task8-build.log`, `target/task8-tv2.log`, `target/task8-network.log` và
`target/surefire-reports/`; không commit output target. Test tập trung có thể tái chạy:

```powershell
mvn "-Dtest=MatchGameplayTest,GameplayProtocolTest,ItemClientStateTest,MatchResultsPanelTest,MatchLifecycleIntegrationTest" test
```

## Checklist, giới hạn và bước tiếp theo

- [x] Spawn/drop từ block mềm, không trùng ô; item cũ trúng blast bị xóa.
- [x] Pickup tự động, tranh cùng tick chỉ một người nhận; caps/quota/range/speed authoritative.
- [x] Damage/death từ cả batch trước quyết định kết quả; chết không tiếp tục input/pickup/plant.
- [x] LAST_ALIVE/ALL_DEAD/TIMEOUT, thứ hạng đồng hạng, một kết quả bất biến mỗi trận.
- [x] GAME_OVER chính thức, full snapshot cuối, bảng kết quả và ready/start chơi lại.
- [x] Hai client TCP thấy cùng item/chỉ số/kết quả; snapshot và event không cộng tác động hai lần.
- [x] Disconnect/Leave/End hủy lượt; cleanup runtime/worker/entity; replay map/chỉ số/alive/ID mới.
- [x] Hồi quy M1/M2/bom, codec v2/v3 và build Java 17.
- [ ] Nghiệm thu trực tiếp Swing: pickup/HUD, đóng cửa sổ khi tự kết thúc, focus Space, bảng kết quả và chơi lại nhiều lượt.
- [ ] Nhiều máy LAN với 2/4 client, jitter/mất mạng thực tế và shutdown dài hạn.
- [ ] Stress queue/backlog, rate limit/slow consumer, lỗi runtime và start/stop dài hạn: nhiệm vụ 9.
- [ ] Nhóm xác nhận cân bằng/rubric cuối cùng; các giá trị hiện tại là cấu hình demo.

Bước tiếp theo: nhiệm vụ 9 — củng cố heartbeat/disconnect/queue/rate limit và
cleanup dưới lỗi/stress. Không triển khai reconnect ngoài phạm vi đã thống nhất.

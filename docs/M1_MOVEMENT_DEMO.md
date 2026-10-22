# M1 — demo di chuyển multiplayer

Tài liệu này mô tả profile M1 giữ làm regression. Launcher mặc định hiện dùng
[M2 lobby](M2_LOBBY_LIFECYCLE.md); chạy M1 GUI cần `--demo` như lệnh bên dưới.

Ngày: 21/10/2026. Có bản chạy server/client qua TCP cho 2–4 người, một phòng demo cố định,
di chuyển/va chạm, prediction/reconciliation/interpolation, full/delta và resync.
**Đã kiểm chứng tự động qua TCP và tiến trình Java riêng; chưa nghiệm thu thao tác Swing/LAN nhiều máy.**
Chưa ghép bom/item, lobby tạo phòng, ready/chat hoặc reconnect.

## 1. Chạy nhanh trên Windows

Cần JDK 25+ với java/javac trên PATH. Chạy từ gốc repository; build một lần trước khi mở các terminal.
Script build không cần Maven hoặc tải thư viện vì production hiện chỉ dùng Java chuẩn.

```powershell
cmd /c scripts\build-demo.cmd
```

Terminal server (mặc định port 8888, số người 2):

```powershell
cmd /c scripts\run-server.cmd 8888 2
```

Hai terminal client riêng:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Chinh --demo
```

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Mai --demo
```

Mỗi lệnh client mở một cửa sổ riêng. Cửa sổ chờ hiện trước; đủ số người cấu hình thì các
cửa sổ chuyển sang trận. Phím mũi tên di chuyển nhân vật xanh, nhân vật khác màu hồng.
Nhả phím/mất focus gửi mẫu đứng yên. Đóng cửa sổ để ngắt kết nối.

Muốn 3 hoặc 4 người: chạy server với tham số cuối 3/4 và mở đúng số client. Roster cố định
trong trận, không bổ sung người giữa trận. Client vượt số người bị từ chối; khi tất cả rời,
runtime được dọn và server chờ nhóm mới với matchId mới. ID/spawn cấp theo roster server,
không giả định client kết nối đầu tiên luôn là Player 1.

Chạy LAN: server giữ nguyên lệnh, client thay 127.0.0.1 bằng IP LAN máy server; các máy dùng
cùng mã/build và port đã cấu hình. Nếu cần, cho phép Java/port đó trong firewall của máy server.
Chưa có kết quả chạy LAN thực trong lượt này.

Nếu dùng Maven đã cấu hình, có thể thay script build/run bằng:

```powershell
mvn test
java -cp target/classes com.bombit.server.app.ServerApplication 8888 2
java -cp target/classes com.bombit.client.app.ClientApplication 127.0.0.1 8888 Chinh
```

Lệnh java client chạy ở terminal khác với server. Các script dùng target/demo-classes,
còn Maven dùng target/classes; chạy lại build tương ứng sau khi sửa mã.

## 2. Luồng đã ghép

CONNECT/ACK → tự vào phòng demo → đủ roster → startGame → GAME_STARTED có mapping/matchId
→ một worker đọc pollUpdate → full trước input → MOVE 50 ms → snapshot/ACK → client reconcile/render.

- DemoGameServer là composition root, tái sử dụng Server, GameRuntimeManager, MovementHandler,
  PacketEncoder/writer queue và SnapshotPacketCodec; không tự viết socket server thứ hai.
- Phòng cố định UUID 00000000-0000-4000-8000-000000000001. Số người chọn lúc chạy (2..4),
  kết nối hợp lệ tương đương tự sẵn sàng trong **profile M1**. Không thay luật ready của lobby chuẩn.
- Profile chờ dùng ROOM_STATE `{profile:"MOVEMENT_DEMO",roomId,state:"WAITING",joined,expected}`;
  không quảng cáo đã có ROOM_STATE lobby đầy đủ. GAME_STARTED dùng schema canonical của DemoPacketCodec.
- Map 17×15, tile 32 pixel, hitbox 24×24, speed demo 150 pixel/s; bốn spawn tách nhau,
  tường biên, tường giữa và hai block mềm chỉ để va chạm. Không có hệ thống phá block/bom trong runtime.
  Cấu hình này chỉ cho demo, không tự chốt lựa chọn cân bằng B1–B6 của game đầy đủ.
- Một consumer snapshot/runtime; metadata được enqueue trước full, header matchId ở hai chiều;
  handler xác thực session/PLAYING/membership/match và full đã enqueue trước nhận input.
- MOVE tối đa 40 request/giây/connection, resync tối đa một/giây. Request lastAppliedTick không
  được dùng để rewind hoặc chặn server; response là full mới hơn mốc publish server tại lúc yêu cầu.
- Full resync phát tới mọi client cùng baseline, qua FULL_STATE_SYNC. Client nghe cả hai loại
  snapshot, bỏ packet khác match, giữ world cũ khi sai baseline và tự xin full; input tạm ngưng khi cần full.
- DemoClientConnection gắn listener trước CONNECT, đưa metadata/snapshot qua executor UI tuần tự
  có giới hạn backlog, duy trì PING/timeout. MovementGameWindow dùng cùng controller đã nhận metadata,
  không tạo listener mới sau khi full đầu đã đi qua. Tắt queue pull trùng lặp cho client chỉ dùng listener.
- Khi rời/mất mạng, M1 bỏ player trên game thread; còn người thì trận tiếp tục, hết người thì dọn
  runtime/worker. Session transport có thể giữ dữ liệu grace 15 giây nhưng M1 không nhận reconnect vào trận.
  Đây là chính sách profile demo, không phải quyết định B1/reconnect của bản game đầy đủ.
- Khi tắt server/lỗi mạng, controller dừng gửi, UI báo lỗi và dừng input/render. Close không chặn EDT bằng socket send.

## 3. Kiểm thử thực chạy

**Maven test: 45 test, 0 failure/error/skipped**, compiler target 17 trên JVM 25.
Gồm 36 test trước M1, 8 MovementDemoTest và 1 MovementDemoProcessTest.

| Bài kiểm tra mới | Bằng chứng |
|---|---|
| Hai client TCP độc lập | ID riêng/match giống nhau; sample MOVE đổi vị trí đúng 7,5 pixel, ACK đến cả hai, STOP không trôi |
| Biên/map | TCP kiểm tra không xuyên biên, block mềm và tường giữa; dung sai tiếp xúc theo collision chia bước <=1 pixel hiện có |
| Ba client | Đủ ba người mới start, ba ID riêng, full chung match |
| Bốn client + client thứ năm | Bốn replica full đúng, client thứ năm ROOM_FULL; một người rời, ba người còn lại tiếp tục nhận MOVE/snapshot |
| Validation/rate limit | MOVE khi đang chờ → NOT_PLAYING, match cũ → STALE_MATCH, spam MOVE/resync → RATE_LIMITED |
| Resync raw TCP | Sai baseline giữ world; request tick client rất lớn vẫn nhận full; các peer nhận full chung |
| Client/controller thật | Listener trước handshake nhận full đầu; prediction gửi MOVE và peer nhận ACK; gap tổng hợp bắt buộc controller tự xin full mới |
| Mất server | Hai controller thật đóng và báo disconnect qua callback; không tiếp tục input |
| Dọn/chơi nhóm mới | Phòng trống không còn worker/runtime hiện tại; roster sau có matchId mới |
| Ba tiến trình thật | ServerApplication + hai ClientApplication --headless ở ba JVM; mỗi client READY players=2 và FINAL ack dương, exit 0 |

Chế độ headless chạy cùng DemoClientConnection/MovementClientController như Swing, tự gửi
sample để smoke test; không mô phỏng phím hoặc chứng minh cửa sổ đã được thao tác bằng tay.
Ví dụ, mở hai terminal khi server chờ hai người:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 TestA --headless 4
```

Client tự kết thúc sau bốn giây. Smoke test tự động dùng port động để không chiếm port demo.
Log: target/m1-tests.log, target/m1-server-process.log, target/m1-client-a-process.log,
target/m1-client-b-process.log; build log target/m1-build-demo.log; báo cáo JUnit target/surefire-reports.
Ngoài JUnit còn chạy bộ Check TV2 và NetworkTest main; kết quả được lưu ở target/m1-tv2.log,
target/m1-network.log. Output target là tạm, không commit.

Lượt test bổ sung đã phát hiện lỗi cleanup reader khi shutdown trong callback runtime;
Server nay đóng socket rồi cho reader hoàn tất trước khi buộc interrupt. Test collision ban đầu
đòi tiếp xúc chính xác từng phân số pixel, được sửa thành kiểm tra không xuyên và khoảng hở <1 pixel
phù hợp thuật toán hiện có; không sửa luật collision để ép test đạt.

## 4. File tạo/sửa

Các đường dẫn Java dưới đây tính từ src/main/java/com/bombit. Xem sổ phân công để có link đầy đủ.

| File | Phụ trách | Thay đổi |
|---|---|---|
| server/app/DemoGameServer.java (mới), ServerApplication.java | TV4 tích hợp; phối hợp TV1/TV2 | Roster cố định, runtime, routing/rate limit, broadcast/resync/cleanup, launcher |
| client/app/DemoClientConnection.java (mới), ClientApplication.java | TV4 tích hợp; phối hợp TV2 | Handshake/heartbeat, hàng đợi UI, chuyển màn hình, launcher Swing/headless |
| common/protocol/DemoPacketCodec.java (mới), SessionPacketCodec.java | TV4 | GAME_STARTED/profile chờ, encode CONNECT/decode ACK/ERROR |
| server/dispatch/RequestRejectedException.java (mới), SessionHandlers.java | TV4 + TV1 | Mã lỗi nghiệp vụ, hook sau ACK |
| server/network/Server.java, ClientHandler.java | TV1 + TV4 | Hook session lifecycle, lỗi nghiệp vụ, shutdown reader trước interrupt |
| client/network/ClientNetwork.java | TV4 | Tùy chọn listener-only không làm đầy queue pull |
| client/game/movement/MovementClientController.java | TV2 | Match envelope, hai type snapshot, entry nhận ordered packet, gate input |
| client/game/movement/InputController.java, MovementGameWindow.java | TV2 | Tái sử dụng controller, tạm ngưng sample khi resync, dừng khi lỗi |
| src/test/java/com/bombit/integration/MovementDemoTest.java (mới), MovementDemoProcessTest.java (mới) | TV4 + TV2/TV1 | TCP production và launcher nhiều JVM |
| scripts/build-demo.cmd, run-server.cmd, run-client.cmd (mới) | TV4 tích hợp | Build Java 17 và lệnh chạy |
| docs/M1_MOVEMENT_DEMO.md (mới); README.md; docs/protocol.md; docs/PROJECT_COMPLETION_PLAN.md; docs/SERVER_TRANSPORT_STATUS.md; docs/FILE_OWNERSHIP_NOTES.md | Chung / TV4 cho protocol | Hướng dẫn, checklist và kiểm kê |

## 5. Checklist và phần còn phải nghiệm thu

- [x] ServerApplication/ClientApplication có đường chạy M1 thật, không còn chỉ in scaffold.
- [x] Runtime/MovementHandler/snapshot/resync/session/writer được nối production qua TCP.
- [x] Profile 2/3/4 client, ID/match/full đầu và cleanup có test thực chạy.
- [x] Hai client ở JVM độc lập nhận full, gửi sample và nhận ACK qua launcher thật.
- [x] Script build Java 17 biên dịch thành công; test regression/transport cũ vẫn đạt.
- [ ] Mở Swing bằng tay: giữ/nhả/đổi phím, mất focus, quan sát remote interpolation và đóng cửa sổ.
- [ ] Hai máy LAN, IP/firewall thực tế; delay/đọc chậm dài hạn và soak test.
- [ ] Chạy trên JVM 17 thật; hiện test JVM 25 với compiler release 17.

M1 đã có bản phần mềm chạy được và bằng chứng tự động; phần nghiệm thu thủ công trên chưa được
đánh dấu xong. Không có bom/item, reconnect, chat, tạo phòng, host/ready hoặc game over ở profile này.
**Bước tiếp: nhiệm vụ 5/M2 — lobby và vòng đời trận đầy đủ**, giữ M1 và các test TCP này làm regression.

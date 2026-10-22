# Nhiệm vụ 3 — đường truyền server

Đây là báo cáo theo phạm vi nhiệm vụ 3. Bước ghép movement tiếp theo đã được theo dõi tại
[M1_MOVEMENT_DEMO.md](M1_MOVEMENT_DEMO.md); Server transport thuần vẫn dùng độc lập cho regression.

Ngày kiểm chứng: 21/10/2026. Phạm vi: TCP server, CONNECT/session, PING/PONG,
DISCONNECT, decoder/router, writer queue và cleanup. **Chưa ghép gameplay/lobby UI**.
Contract theo [protocol.md](protocol.md), baseline từ [M0](M0_INTEGRATION_CONTRACT.md).

## 1. Hành vi thực tế

- Server tạo phiên CONNECTED tạm khi accept, chỉ cho CONNECT trước handshake. CONNECT version 2,
  tên hợp lệ chuyển IN_LOBBY, trả UUID/token và thông số heartbeat bằng ACK unicast qua writer.
- SessionPacketCodec parse object phẳng cho CONNECT/PING/DISCONNECT, nhận whitespace/thứ tự
  field khác nhau, decode escape/Unicode; từ chối duplicate/unknown field, sai kiểu,
  control character trong tên, surrogate lỗi. Đây không phải parser JSON cho toàn bộ gameplay/lobby.
- ClientHandler dùng PacketDecoder → MessageRouter với ID phiên server giữ. Hiện đăng ký
  CONNECT/PING/DISCONNECT; type không có handler trả UNKNOWN_MESSAGE.
- Length/UTF-8 lỗi đóng kết nối; control payload sai trả INVALID_PACKET; version sai trả
  UNSUPPORTED_VERSION rồi đóng; thiếu handshake trả NOT_CONNECTED rồi đóng.
  Duplicate CONNECT không đổi danh tính. Packet bị từ chối/byte rác không refresh heartbeat.
- Mỗi decode có deadline trọn frame mặc định 10 s; read ngắn không gia hạn deadline.
  Server(port,timeoutMillis) cho phép test deadline ngắn, không đổi mặc định production.
- Một writer/socket dùng PacketEncoder cho framing/giới hạn; OutboundMessage có adapter Packet
  và validation UTF-8. Queue hữu hạn 256, đầy hoặc I/O lỗi đóng riêng socket và cleanup một lần.
  Error cuối handshake được drain có giới hạn; close đóng socket để unblock rồi join writer.
- Writer giữ socket lúc tạo; reader/release kiểm tra socket sở hữu để tránh dọn/gửi vào socket thay thế.
  Đây là nền tảng ownership, chưa phải reconnect end-to-end.
- EOF/mất mạng sau handshake chuyển DISCONNECTED và giữ dữ liệu phiên theo grace 15 s,
  không giữ writer/socket. USER_QUIT và handshake/frame lỗi nghiêm trọng hard drop.
- Reader pool hữu hạn 32 worker + 32 chỗ chờ; shutdown đóng socket trước await reader,
  close idempotent. SessionManager xử lý race schedule timer sau khi scheduler đóng.

Session CONNECTED tạm nằm trong registry để router/writer tra cứu, không phải người chơi đã vào lobby.
Chưa có runtime production nên disconnect chưa chuyển thành command gameplay.

## 2. Kiểm thử thực chạy

**Maven offline test: BUILD SUCCESS; 36 test, 0 failure/error/skipped**:
23 test trước nhiệm vụ này + 10 ServerTransportTest + 2 SessionWriterTest + 1 regression
shutdown thêm trong SessionManagerTest. JVM Java 25, compile target Java 17.

| Kiểm tra | Bằng chứng đạt |
|---|---|
| Handshake chia frame | Ba byte header chưa có ACK; gửi tiếp từng byte nhận ACK UUID/token và IN_LOBBY |
| Gộp/thứ tự frame | CONNECT + hai PING trong một buffer, nhận ACK rồi hai PONG đúng thứ tự; tên escape decode đúng |
| Handshake sai | Thiếu CONNECT, version cũ, duplicate/sai kiểu/null/control/Unicode lỗi, trailing comma/nested object bị từ chối |
| Payload lỗi/unknown | ERROR, không thay identity/liveness; valid PING sau đó vẫn hoạt động |
| Framing lỗi | Length nhỏ/lớn và UTF-8 lỗi đóng socket, dọn session/writer |
| EOF giữa body | Half-close khi PING chưa đủ; đóng kết nối, phiên authenticated chuyển DISCONNECTED |
| Deadline | Header dở hết hạn test 150 ms được dọn |
| Writer đồng thời | Hai producer gửi 40 frame, decode đủ 40 body riêng không xen byte |
| Shutdown | Đóng server khi reader chờ header/body; writer đã bắt trong test đều dừng, registry rỗng |
| Queue đầy | Output giả lập có latch chặn write, queue capacity 1 đầy → socket đóng, worker dừng, callback một lần |
| Writer validation | Target session sai, UTF-8 lỗi, frame quá lớn bị từ chối trước queue |
| Race scheduler | Latch giữ disconnect trong callback, close manager rồi thả; không exception, session CLOSED |

Fixture kiểm tra cả lỗi uncaught reader qua Server.getConnectionFailure(). Lượt đầu phát hiện
RejectedExecutionException nền dù JUnit xanh; đã sửa và thêm regression trước kết quả cuối.
Ngoài JUnit: 4 Check của scripts/check-tv2.cmd PASS; NetworkTest main TCP loopback PASS.
Log: target/task3-tests.log, target/task3-tv2.log, target/task3-network.log, target/surefire-reports.
Các log là output tạm, không commit, có thể bị xóa khi clean.

## 3. File tạo/sửa và trách nhiệm

Các đường dẫn rút gọn dưới đây nằm trong src/main/java/com/bombit, trừ test/docs ghi riêng.
Đường dẫn đầy đủ được kiểm kê tại [FILE_OWNERSHIP_NOTES.md](FILE_OWNERSHIP_NOTES.md).

| File | Phụ trách | Thay đổi |
|---|---|---|
| common/protocol/SessionPacketCodec.java (mới) | TV4 | Control JSON, ACK/ERROR |
| server/dispatch/SessionHandlers.java (mới) | TV4 + TV1 | CONNECT/PING/DISCONNECT handlers |
| server/network/ClientHandler.java | TV1 + TV4 | Decode/route, handshake, deadline, cleanup |
| server/network/Server.java | TV1 | Router/handler, pool hữu hạn, shutdown, chẩn đoán reader |
| server/network/SessionWriter.java | TV1 | Queue frame, socket cố định, framing chung, lỗi/drain/join |
| server/network/ConnectionManager.java | TV1 | Callback lỗi, release theo socket |
| server/network/OutboundMessage.java | TV1 + TV4 | Adapter Packet và validation UTF-8 |
| server/session/Session.java | TV1 | completeHandshake đặt tên và IN_LOBBY một lần |
| server/session/SessionManager.java | TV1 | Closed flag, chống schedule khi shutdown, dọn timer muộn |
| src/test/java/com/bombit/server/network/ServerTransportTest.java (mới) | TV1 + TV4 | 10 test TCP thật |
| src/test/java/com/bombit/server/network/SessionWriterTest.java (mới) | TV1 | 2 test writer |
| src/test/java/com/bombit/server/session/SessionManagerTest.java | TV1 | Thêm test race, giữ hai test cũ |
| docs/SERVER_TRANSPORT_STATUS.md (mới) | Chung | Báo cáo/checklist nhiệm vụ 3 |
| docs/protocol.md; docs/M0_INTEGRATION_CONTRACT.md; docs/PROJECT_COMPLETION_PLAN.md; README.md; docs/FILE_OWNERSHIP_NOTES.md | TV4 / Chung | Tiến độ thực tế, cách chạy, nhật ký/kiểm kê |

Không sửa luật bom/item/movement, không commit/reset và không quy nhận thay đổi có sẵn là công việc lượt này.

## 4. Checklist và phần chưa kiểm chứng

- [x] ClientHandler production dùng decoder/router, CONNECT/ACK và writer queue.
- [x] PING/PONG/DISCONNECT, lỗi packet và handshake có test TCP thật.
- [x] Frame chia/gộp/sai/EOF dở/deadline, writer nhiều producer và shutdown có bằng chứng.
- [x] Queue validation/backpressure và race shutdown có test có kiểm soát.
- [ ] RECONNECT packet/generation handshake: chưa đăng ký, vẫn UNKNOWN_MESSAGE.
- [ ] Gameplay/runtime/snapshot/resync: chưa nối, thuộc nhiệm vụ 4.
- [ ] CREATE/JOIN/READY/CHAT/UI lobby: chưa nối, thuộc nhiệm vụ 5.
- [ ] Hai cửa sổ game, LAN nhiều máy, JVM 17 thật: chưa kiểm chứng.
- [ ] Hết heartbeat 10 s/grace 15 s qua ứng dụng, tải vượt pool, soak test: chưa nghiệm thu.
- [ ] Client chậm qua TCP/OS và socket reconnect cũ đóng muộn: chưa nghiệm thu;
  test queue đầy dùng output có latch, không phải benchmark mạng chậm.

## 5. Chạy lại và bước tiếp

```powershell
mvn test
cmd /c scripts\check-tv2.cmd
java -cp "target/classes;target/test-classes" com.bombit.integration.NetworkTest
java -cp target/classes com.bombit.server.network.Server 8888
```

Môi trường lượt này dùng Maven 3.9.12 trong cache wrapper, JAVA_HOME=D:/JavaJDK như báo cáo
nhiệm vụ 1; không đổi cấu hình hệ thống. Offline cần dependency đã có trong cache.
ServerApplication/ClientApplication chưa là launcher gameplay. tools/manual-client là công cụ
legacy; PING trước CONNECT nay bị từ chối đúng contract.

**Bước tiếp: nhiệm vụ 4 — movement multiplayer.** Dùng Server.getRouter(), session codec,
ClientNetwork và writer adapter; thêm phòng demo/runtime manager dùng chung, mapping/match envelope,
MOVE handler, snapshot worker/full/resync và hai launcher/cửa sổ. Giữ các test transport làm regression.

# Nhiệm vụ 9 — Củng cố độ ổn định

Ngày kiểm chứng: 22/10/2026. Đã sửa các đường heartbeat, queue/backpressure,
client đọc chậm, callback/runtime failure và cleanup; kiểm thử lỗi TCP và stress
start/stop cục bộ đạt. Đây không phải chứng nhận tải lớn hoặc chạy ổn định nhiều giờ.
Reconnect được [đánh giá riêng](RECONNECT_ASSESSMENT.md), chưa nối vào ứng dụng.

## Những vấn đề đã xử lý

- Writer trước đây chỉ thoát khi queue đầy/EOF/close: peer vẫn gửi PING nhưng
  không đọc có thể giữ write kẹt lâu. Nay có watchdog đóng đúng captured socket
  khi một lần ghi quá deadline; thêm giới hạn byte thay vì chỉ đếm frame.
- Callback lỗi có thể chặn đăng ký timer cleanup hoặc làm heartbeat/chat worker
  ngừng xử lý. SessionManager ghi nhận RuntimeException và vẫn hoàn tất cleanup;
  heartbeat cô lập từng session, chat cô lập từng recipient/message.
- Callback writer failure được đưa sang queue cleanup riêng, tránh chạy callback
  lobby/session ngay khi producer còn giữ khóa room/lifecycle. Queue hữu hạn,
  callback vẫn kiểm tra socket được capture, không xử lý một kết nối thay thế.
- Send/close/drain của writer và enqueue/close chat được tuần tự hóa tại phần
  thay trạng thái queue; không nhận frame mới sau dấu kết thúc drain.
- Client pull queue trước đây âm thầm bỏ packet khi đầy; listener lỗi chỉ ghi log.
  Nay client đóng kết nối và báo lỗi, tránh tiếp tục áp dụng delta sau khi mất dữ liệu.
- Control queue ứng dụng có giới hạn số task; UI dispatch và controller standalone
  có giới hạn task/byte. Client write watchdog đóng socket khi server không đọc.
- Client close đóng socket trước khi join reader, không giữ monitor khi chờ thread;
  close gỡ listener và dừng watchdog/control/sender. Controller failure báo một lần;
  callback lỗi từ match cũ không đóng match mới. Khi mất kết nối, giữ controller
  **đã đóng** để UI M1 đọc trạng thái lỗi; không giữ sender/listener đang chạy.
- Lỗi RuntimeException bất ngờ trong handler được ghi nhận, trả SERVER_ERROR và
  đóng connection đó; không tiếp tục trên trạng thái xử lý dở. Validation lỗi vẫn
  dùng INVALID_PACKET và chính sách hiện có.

## Giới hạn vận hành hiện tại

| Thành phần | Giới hạn / hành vi |
|---|---|
| Frame TCP | Tối đa 1.048.576 byte length gồm type/payload; deadline đọc cả frame mặc định 10 s, không gia hạn theo từng byte |
| Heartbeat server | Timeout 10 s, sweep 2 s; pending chưa handshake bị hard drop, session đã handshake sang DISCONNECTED |
| Metadata sau disconnect | Giữ tối đa 15 s theo transport cũ; không giữ ghế/room/runtime vì match bị ABORTED |
| Retained sessions | Tối đa 256, gồm provisional và metadata trong grace; vượt đóng socket mới |
| Reader pool | 32 worker + 32 task chờ; task bị từ chối được cleanup |
| Writer mỗi socket | Queue 256 frame; tổng byte đã nhận gồm frame đang ghi tối đa 4 MiB; overflow đóng connection |
| Write deadline server | 5 s mỗi lần ghi/flush; watchdog kiểm tra mỗi 25 ms |
| Writer failure cleanup | Một worker, queue 256 task; đóng server thì drain/join theo hạn hữu hạn |
| Rate tổng | 100 frame giải mã/giây/connection, gồm CONNECT và request bị từ chối/unknown; frame thứ 101 trả RATE_LIMITED và đóng |
| Rate CHAT / LIST_ROOMS | Mỗi loại 5 request/giây/connection; vượt trả RATE_LIMITED, connection còn dùng được nếu chưa vượt tổng |
| MOVE / PLANT_BOMB | Giữ 40 MOVE và 5 request đặt bom mới/giây; requestId trùng không enqueue |
| REQUEST_FULL_STATE | Giữ tối thiểu 1 s giữa hai yêu cầu server chấp nhận |
| Chat | Queue 256 message; đầy trả CHAT_QUEUE_FULL, close xóa pending và join worker |
| GameLoop | Queue command 4096, outbound snapshot 1024; builder chỉ commit khi enqueue thành công, không bỏ delta rồi tiếp tục baseline sai |
| Gameplay notice | Backlog 4096; vượt làm runtime fail/cleanup theo nhiệm vụ 7–8 |
| Client pull | 1024 packet / 4 MiB; overflow đóng connection và báo lỗi |
| Client UI | 256 task / 4 MiB packet bytes; overflow dừng client/controller, báo một lần |
| Client control | Tối đa 128 send task; thêm tối đa một heartbeat định kỳ và một resync đang chờ |
| Client movement sender | Queue 256 task, payload input nhỏ; overflow dừng controller và báo owner |
| Client liveness/write | PING mỗi 2 s, không nhận traffic 10 s thì lỗi; ghi quá 5 s đóng socket, watchdog mỗi 50 ms |

Các giới hạn frame/queue không phải số đo RAM tổng: còn object, decode buffer,
frame đang encode và dữ liệu replica. Giới hạn byte trên UI/pull là byte wire,
không phải heap size của Java object. Giới hạn capacity pool/session được rà mã;
chưa benchmark hệ thống ở mức trần 32/64/256 connection.

Client chỉ dùng listener như GameClientConnection tắt duplicate pull queue.
Controller dùng độc lập không sở hữu socket: khi controller báo lỗi, caller
chịu trách nhiệm đóng network dùng chung; ứng dụng Demo đã nối đường này.

## File đã thêm/sửa

Đường dẫn production tương đối với `src/main/java/com/bombit/`:

| Nhóm | File |
|---|---|
| Writer/deadline/cleanup | `server/network/SessionWriter.java`, `ConnectionManager.java` |
| Admission/rate/handler fault | `server/network/Server.java`, `ClientHandler.java`, `InboundRateLimiter.java` (mới) |
| Heartbeat/session/chat | `server/heartbeat/HeartbeatManager.java`, `server/session/SessionManager.java`, `server/chat/ChatHandler.java` |
| Client socket/queue/listener | `client/network/ClientNetwork.java` |
| Client control/UI/lifecycle | `client/app/GameClientConnection.java`, `client/game/movement/MovementClientController.java` |

Không thêm handler RECONNECT, không thay schema MOVE/ACK/snapshot hoặc luật ABORTED.
Diagnostics bổ sung: writer pendingFrames/pendingBytes, worker termination,
session pendingDisconnectTimers/failure, heartbeat/chat/connection failure,
client listenerCount/reader/sender/control termination.

Test thêm/sửa dưới `src/test/java/com/bombit/`:

- `server/network/TransportStabilityTest.java`: 6 test heartbeat, drip frame,
  socket ghi kẹt dù peer vẫn PING, handler fault, flood/rate và ranh giới cửa sổ rate.
- `server/network/SessionWriterTest.java`: thêm 1 test byte budget trước count limit;
  2 test cũ queue-full/framing tiếp tục đạt.
- `server/session/SessionFaultTest.java`: 1 test callback lỗi không hủy sweep/grace cleanup.
- `server/chat/ChatFaultTest.java`: 1 test recipient lỗi không làm mất message của recipient khỏe.
- `client/network/ClientStabilityTest.java`: 7 test pull/UI/controller queue và byte
  overflow, listener lỗi, client write deadline, dừng worker và gỡ đăng ký.
- `integration/StabilityLifecycleTest.java`: 3 test runtime fault + replay, 20 vòng
  trận trên hai connection, 10 lần tạo/đóng server với reader đang chờ frame dở.

Tài liệu cập nhật: README, PROTOCOL, docs/protocol, PROJECT_COMPLETION_PLAN,
báo cáo này, RECONNECT_ASSESSMENT và ghi chú chuyển tiếp TASK8_ITEMS_RESULTS.

## Kết quả thực chạy

JDK 25.0.1 compile target Java 17, Maven 3.9.12 offline, chạy ngoài sandbox để
dùng JUnit cache và TCP đa tiến trình như những lượt trước.

| Kiểm tra | Kết quả 22/10/2026 |
|---|---|
| `mvn test` toàn bộ sau sửa cuối | **108 test, 0 failure, 0 error, 0 skipped; BUILD SUCCESS** |
| Test mới nhiệm vụ 9 trong suite | **19 test mới**, bao gồm 3 test lifecycle/stress |
| Bộ tập trung trước test controller standalone cuối | 20 test đạt, gồm 2 test writer cũ |
| Stress trận | 20 lượt Ready/Start → đặt bom → End, hai connection giữ nguyên; matchId mới, runtime/world/sender dọn, listener mỗi client giữ ở 1 |
| Stress server | 10 lần tạo/start/close, reader chờ frame dở được đánh thức; không còn worker mới thuộc các nhóm thread được kiểm tra |
| Lỗi runtime | Sau đặt bom, extension ném lỗi: hai client nhận ABORTED, world/worker được dọn, cùng connection start trận mới được |
| Client đọc chậm | TCP loopback thật, buffer nhỏ, ghi frame lớn và peer vẫn gửi PING; watchdog đóng writer, connection khác vẫn trả PONG |
| `scripts/build-demo.cmd` | Exit 0 |
| `scripts/check-tv2.cmd` | Exit 0; API/movement/runtime/delta checks đạt |
| NetworkTest main riêng | PASS codec/framing/validation/router/metrics/TCP loopback |

Lần full đầu phát hiện test M1 cần đọc controller đã đóng sau disconnect; đã
sửa giữ tham chiếu trạng thái này, không nới test hoặc bỏ kiểm tra cleanup.
Lần full cuối đạt toàn bộ 108 test.

Log cục bộ không commit: `target/task9-tests.log`, `task9-tests-first.log`,
`task9-focused.log`, `task9-build.log`, `task9-tv2.log`, `task9-network.log`
và `target/surefire-reports/`. Lệnh tái chạy riêng:

```powershell
mvn "-Dtest=TransportStabilityTest,SessionWriterTest,SessionFaultTest,ChatFaultTest,ClientStabilityTest,StabilityLifecycleTest" test
```

## Checklist và phần chưa kiểm chứng

- [x] Heartbeat hợp lệ giữ session sống; silent/provisional hết hạn được cleanup.
- [x] Frame chia dở/drip không kéo dài deadline; EOF/invalid frame giữ xử lý cũ.
- [x] Queue hữu hạn theo count/byte; overflow không âm thầm mất snapshot rồi tiếp tục.
- [x] Flood tổng, CHAT/LIST và rate gameplay/resync; invalid traffic không được miễn quota tổng.
- [x] Server/client write kẹt được socket watchdog đánh thức.
- [x] Callback/session/chat/handler/runtime lỗi được cô lập, ghi nhận và cleanup.
- [x] Thread/socket/scheduler/listener dọn qua 20 trận và 10 server cycle cục bộ.
- [x] Reconnect được đánh giá riêng, vẫn không đăng ký route ở server ứng dụng.
- [ ] Soak test nhiều giờ, tải đồng thời gần giới hạn, memory/CPU profile và churn dài hạn.
- [ ] Nhiều máy LAN, packet loss/jitter, firewall và mất mạng thực tế.
- [ ] Stress Swing trực tiếp với focus/animation timer/window dispose; test hiện dùng UI executor và offscreen.
- [ ] Callback/extension tùy biến không hợp tác hoặc block vô hạn: Java không bảo đảm cưỡng bức dừng thread; cần giới hạn công việc extension.

Bước tiếp theo là nhiệm vụ 10: đo mạng 1/2/4 client, CSV, hướng dẫn LAN, đóng
gói và bàn giao. Nếu cần reconnect, tách thành một lượt riêng theo đánh giá đã liên kết.

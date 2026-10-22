# Đo mạng 1/2/4 client

Baseline hiện hành đã chuyển sang Java 25: xem [JDK25_MIGRATION.md](JDK25_MIGRATION.md).
Các số đo và kết quả target 17 dưới đây là lịch sử trước chuyển đổi.

## Kết quả thực đo — 22/10/2026

CSV gốc: [loopback-2026-10-22.csv](measurements/loopback-2026-10-22.csv),
run-id `task10-loopback-20261022`, source `f9bdab1-dirty`.
Windows 11 10.0 amd64, Java 25.0.1, 16 logical processors; bytecode target 17.
9 kịch bản, **25 dòng client**, mỗi kịch bản warmup 1 giây + đo khoảng 5 giây.
Server và các client dùng TCP thật nhưng **cùng JVM, cùng máy, loopback**.
Bộ hồi quy cũng chạy trong cùng giai đoạn; không kiểm soát hoàn toàn tải nền hệ điều hành.
Chỉ chạy một lượt, không có confidence interval hoặc kết luận về hiệu năng LAN/tải tối đa.

TX/RX dưới đây là tổng client → server / server → client, đơn vị **byte ứng dụng/giây**.
Cột p95 là **giá trị p95 lớn nhất trong các client của kịch bản**, không phải p95 gộp mẫu.
Dấu `—` nghĩa là không phát MOVE nên không có ACK sample.

| Kịch bản | Client | TX B/s | RX B/s | RTT p95 lớn nhất (ms) | MOVE ACK p95 lớn nhất (ms) |
|---|---:|---:|---:|---:|---:|
| Transport | 1 | 94.182 | 94.182 | 2.110 | — |
| Transport | 2 | 188.374 | 188.374 | 2.241 | — |
| Transport | 4 | 376.992 | 376.992 | 2.524 | — |
| Idle gameplay | 2 | 189.964 | 916.226 | 2.052 | — |
| Idle gameplay | 4 | 379.912 | 2104.308 | 2.342 | — |
| Movement | 2 | 4232.646 | 14817.662 | 2.401 | 46.322 |
| Movement | 4 | 8450.904 | 43893.664 | 2.488 | 48.989 |
| Bombs + movement | 2 | 4332.590 | 16135.962 | 2.275 | 49.368 |
| Bombs + movement | 4 | 8647.811 | 49328.876 | 2.178 | 48.871 |

Tất cả dòng PASS và cleanup_ok=true; resync_requests=0. Mỗi client ở bombs-2 thấy 2 bom/2 vụ nổ,
ở bombs-4 thấy 4 bom/4 vụ nổ. Đây là runner headless, không xác nhận trải nghiệm render GUI.
CSV lưu thêm mean/min/max RTT, ACK mean, plant ACK, full/delta snapshot, snapshot bytes,
stale, số input/probe còn pending và metadata từng lượt để kiểm tra riêng.

Đã kiểm tra thêm **3 tiến trình Java riêng trên cùng máy**: một server, một host runner,
một guest runner, mỗi runner sở hữu một client, movement 2 giây + warmup 1 giây.
[Host CSV](measurements/same-host-process-host.csv), [guest CSV](measurements/same-host-process-guest.csv).
Cả hai PASS, có MOVE ACK, cleanup runner đạt. `scope=remote_runner` nghĩa là server nằm ngoài runner;
label ghi rõ cùng máy. **Đây vẫn chưa phải LAN.** Revision `runtime-smoke` là nhãn thử JAR vừa build,
không phải Git commit; lần đo suite ở trên có revision nguồn riêng.

## Công cụ và định nghĩa

`com.bombit.tools.NetworkBenchmark` dùng ClientNetwork/codec/protocol chính thức, không chọc trực tiếp world.
Một client chỉ đo transport; gameplay bắt buộc 2 hoặc 4 người. Các workload:

- `transport`: handshake rồi PING/PONG, không tạo trận.
- `idle`: tạo phòng, ready/start, nhận snapshot, không gửi MOVE.
- `movement`: gửi ý định di chuyển mục tiêu 20 Hz trên hành lang an toàn.
- `bombs`: mỗi người đặt **một bom** đầu cửa sổ, chạy tránh, rồi gửi MOVE stopped;
  không đại diện cho việc đặt bom liên tục hoặc tranh item.
- `suite`: chỉ `--local`, tự chạy transport 1/2/4 và gameplay idle/movement/bombs 2/4.

Probe PING mỗi 200 ms (5 Hz), nhiều hơn heartbeat của sản phẩm. RTT tính thời gian monotonic từ
gửi PING đến runner xử lý PONG; gồm scheduling/queue/polling. MOVE ACK từ lúc gửi input tới snapshot
đầu tiên có lastProcessedInput ≥ sequence, gồm chờ tick và phát snapshot; không phải one-way latency.
Plant ACK từ gửi PLANT_BOMB đến snapshot đầu thấy bom của chính client. p95 dùng nearest-rank.
Mẫu không tồn tại được ghi ô trống, không ghi 0 ms.

TX đếm khi ClientNetwork.send thành công; RX đếm frame runner lấy ra xử lý trong cửa sổ.
Byte dùng độ dài PacketEncoder: length prefix/type/JSON và Base64 nếu payload có nó.
Không tính CONNECT/lobby/warmup, TCP/IP headers, ACK TCP, retransmission hay close/settle.
Đây là lưu lượng tầng ứng dụng, không phải NIC throughput hoặc wire capture.
Tổng B/s trong bảng là tổng tỷ lệ từng dòng (mỗi dòng dùng thời lượng thực của runner).

`pending_ping_end` và `pending_move_end` là tác vụ chưa có phản hồi khi cửa sổ đóng,
không được diễn giải thành packet loss. `cleanup_ok` local kiểm tra cả reader/listener và server/runtime/worker;
remote chỉ kiểm tra client runner, không chứng minh remote server đã cleanup.
PASS yêu cầu kết nối còn sống, RTT/baseline hợp lệ; movement cần ACK, bombs cần đủ bom/nổ và plant ACK.
Lỗi được ghi FAIL/error và chương trình trả exit code khác 0; không cộng dòng FAIL vào kết luận đạt.

Server local dùng thời lượng trận = seconds + warmup + 30 giây để tránh timeout benchmark;
các luật bom mặc định. Khi đo server ngoài, thời lượng đo phải ngắn hơn thời gian trận còn lại;
default trận 180 giây nên hướng dẫn LAN dùng 60 giây. Client không tự đổi luật server.

## Tái chạy

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\run-benchmark.cmd --local --mode suite --seconds 5 --warmup 1 --csv target\my-results.csv --label single-host-loopback --revision YOUR_REVISION --run-id YOUR_RUN_ID
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-distributed.ps1
```

Lệnh cuối cần JAR đã build, chạy server/host/guest thành các process ẩn, ghi CSV/log riêng dưới
target/distributed-smoke và dọn các process nó tạo trong finally. Server process được dừng sau smoke;
không dùng kiểm tra đó để kết luận graceful shutdown server (đã có lifecycle tests riêng).
Muốn kiểm tra JAR đã giải nén: thêm `-RuntimeRoot 'DUONG_DAN_BAN_GIAI_NEN'`.
Script smoke thuộc source, không phải dependency của bản runtime.

CSV UTF-8, dấu phẩy, quoted field theo CSV; append kiểm tra header. Mỗi tiến trình một file,
run-id/label/revision phải mô tả đúng lần đo. Không dùng chung file cho nhiều tiến trình đồng thời.
`timestamp_utc` ghi lúc bắt đầu kịch bản, gồm cả chờ lobby/setup, không phải mốc đồng bộ cửa sổ đo giữa máy.
Các hàng có cùng scenario nhưng khác run-id là lượt khác nhau, không tự gộp chúng.

**Nhóm còn phải chạy** nhiều máy, 3 lượt/mức tải với cửa sổ dài hơn, GUI/soak và ghi topology
theo [LAN_GUIDE.md](LAN_GUIDE.md). Không có số đo LAN, packet loss, CPU/RAM hoặc benchmark tối đa giả định
trong báo cáo này.

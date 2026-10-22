# Nhiệm vụ 10 — Kiểm thử, đo mạng và bàn giao

Baseline hiện hành đã chuyển sang Java 25: xem [JDK25_MIGRATION.md](JDK25_MIGRATION.md).
Các số đo và kết quả target 17 dưới đây là lịch sử trước chuyển đổi.

Ngày thực hiện: 22/10/2026. Phần tự động và tài liệu đã hoàn thiện, có kết quả chạy thực;
nghiệm thu GUI/LAN nhiều máy và soak dài hạn còn mở. Không dùng sự tồn tại của class/script
để thay thế kết quả kiểm chứng.

## File của lượt này

| File | Thay đổi và mục đích |
|---|---|
| `src/main/java/com/bombit/tools/NetworkBenchmark.java` | Runner headless dùng protocol thật; transport 1/2/4, idle/movement/bombs 2/4; local suite hoặc host/guest phân tán; RTT/ACK/bytes/snapshot/event/cleanup và CSV |
| `src/main/java/com/bombit/tools/BenchmarkCsv.java` | CSV UTF-8, escape field, kiểm tra header khi append |
| `src/test/java/com/bombit/tools/NetworkBenchmarkTest.java` | 4 test cấu hình/CSV/transport TCP/bom 4 client và cleanup |
| `scripts/build-demo.cmd`, `scripts/build.ps1` | Build sạch production Java target 17, tạo executable JAR và BUILD_INFO/SHA256 |
| `scripts/java-env.cmd`, `scripts/classpath.cmd` | Chọn Java/classpath dùng chung cho source và ZIP runtime |
| `scripts/run-server.cmd`, `scripts/run-client.cmd`, `scripts/run-benchmark.cmd` | Runner hỗ trợ JAR/classes, đường dẫn có dấu cách và exit code |
| `scripts/test.cmd` | Chạy JUnit rồi Check/Demo TV2 và NetworkTest main, dừng khi lỗi |
| `scripts/check-distributed.ps1` | Smoke server + hai runner Java process riêng; log/CSV và cleanup process sở hữu |
| `scripts/package.cmd`, `scripts/package.ps1` | ZIP runtime gồm JAR/script/docs, SHA256; không kèm test classes/JRE |
| `docs/measurements/*.csv` | 25 dòng suite loopback và 2 dòng host/guest process smoke thực đo |
| `docs/NETWORK_MEASUREMENTS.md` | Phương pháp, bảng số đo, định nghĩa và giới hạn diễn giải |
| `docs/LAN_GUIDE.md`, `docs/PACKAGING.md` | Hướng dẫn nhiều máy, đo phân tán, biên bản còn trống, build/package/checksum |
| `docs/TECHNICAL_REPORT.md`, `docs/DEMO_SCRIPT.md` | Kiến trúc, protocol/threading/luật, hạn chế và kịch bản trình diễn |
| `README.md`, `docs/PROJECT_COMPLETION_PLAN.md`, tài liệu này | Điểm vào bàn giao và checklist theo bằng chứng |

Không bao gồm các file đã sửa ở nhiệm vụ 1–9 trong danh sách thay đổi lượt này.
Working tree đã có các thay đổi đó; chưa tạo commit hay phát hành lên dịch vụ ngoài.

## Kiểm thử thực chạy

Môi trường: Windows 11, JDK 25.0.1, `--release 17`, Maven 3.9.12 với dependency cache offline.
Không suy ra đã chạy trên Java 17 runtime từ tùy chọn biên dịch target 17.

| Lệnh / kiểm tra | Kết quả thực tế |
|---|---|
| `scripts/test.cmd -o` với BOMBIT_MVN và Maven repo local của máy | **112 JUnit tests, 0 failure/error/skipped**, gồm 4 test mới; BUILD SUCCESS |
| Check/Demo TV2 trong test.cmd | PASS; delta check: full 110076 B, delta 16411 B, tiết kiệm 85.09% trên fixture, không phải số đo mạng gameplay |
| NetworkTest main trong test.cmd | PASS: codec, framing, validation, router, metrics, TCP loopback |
| `scripts/build-demo.cmd` | PASS: production JAR + BUILD_INFO, Java target 17 |
| `run-benchmark --local --mode suite --seconds 5 --warmup 1` | **9 kịch bản, 25 dòng PASS**, cleanup=true, 625 RTT samples, pending ping=0, pending move=0, stale=0, resync=0 |
| Bom trong suite | Mỗi client thấy 2/2 hoặc 4/4 bom/vụ nổ; plant ACK 45.529–51.851 ms trên lượt đo này |
| `scripts/check-distributed.ps1` | PASS: server + host/guest là 3 Java process trên cùng máy, 2 client có ACK và CSV riêng |
| `scripts/package.cmd` | PASS: tạo ZIP và SHA256; checksum ZIP kiểm tra khớp |
| Giải nén ZIP vào `target/package smoke task10` | PASS: runner transport chạy qua script từ đường dẫn có dấu cách; JAR đúng main manifest/bytecode Java 17, không chứa Test.class |
| Smoke đa tiến trình dùng JAR đã giải nén | PASS: server + host/guest, ACK/CSV/cleanup runner hợp lệ |

Số đo suite được lưu tại [measurements/loopback-2026-10-22.csv](measurements/loopback-2026-10-22.csv),
không gán thành kết quả LAN. [NETWORK_MEASUREMENTS.md](NETWORK_MEASUREMENTS.md) có bảng 9 kịch bản,
scope, cách tính p95 và cách tái chạy. Snapshot/ACK timing phản ánh cả tick/queue/polling của ứng dụng.

Log kiểm thử cục bộ: `target/task10-tests.log`, `task10-focused.log`, `task10-build.log`,
`task10-benchmark.log`, `task10-distributed.log`, `task10-package.log`, `task10-release-process.log`,
`target/distributed-smoke/`, `target/package smoke task10/` và `target/surefire-reports/`.
`target/` không commit; CSV dưới docs là bằng chứng nhỏ cần giữ cùng source.

## Checklist bàn giao

- [x] Có runner TCP chính thức cho 1/2/4 client; phân biệt transport 1 với gameplay tối thiểu 2.
- [x] Xuất CSV có run-id/revision/topology, số đo thật, lỗi và cleanup; không biến mẫu thiếu thành 0 ms.
- [x] Đã chạy suite loopback, kiểm tra ACK/bom/nổ và smoke host/guest ở process riêng.
- [x] Có build/test/run/package script, JAR metadata, ZIP/SHA256 và hướng dẫn tái tạo.
- [x] ZIP đã giải nén và chạy smoke từ đường dẫn có dấu cách; checksum, manifest và JAR contents được kiểm tra.
- [x] README, LAN guide, technical report, demo script và phương pháp đo đã cập nhật.
- [ ] GUI trực tiếp 2/4 client trên các máy khác nhau: bom/nổ/block/item/HUD/kết quả/replay.
- [ ] Số đo LAN nhiều máy, ít nhất 3 lượt/mức tải; firewall/Wi-Fi/jitter/mất mạng thật.
- [ ] Soak dài hạn, CPU/RAM/thread profiling, tải gần giới hạn và churn dài.
- [ ] Java 17 runtime và các OS ngoài Windows; hiện chỉ biên dịch target 17 trên JDK 25.
- [ ] Nhóm điền biên bản LAN và quay video/demo nghiệm thu.

Reconnect chưa triển khai và không nằm trong checklist đã hoàn thành. Nếu cần, thống nhất phạm vi
riêng theo [RECONNECT_ASSESSMENT.md](RECONNECT_ASSESSMENT.md); không tự thêm vào bản bàn giao này.

Bước tiếp theo: nhóm dùng cùng ZIP/SHA256 làm theo [LAN_GUIDE.md](LAN_GUIDE.md), lưu CSV từng máy,
video và biên bản; đối chiếu checklist còn mở trước khi tuyên bố nghiệm thu toàn bộ sản phẩm.

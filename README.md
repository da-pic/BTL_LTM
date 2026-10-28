# Bomb It Multiplayer — Java project structure

Baseline hiện hành: **JDK/Java 25** cho build, server và client. Xem [cấu hình JDK/IDE và chuyển đổi](docs/JDK25_MIGRATION.md).

Luật item mới: [5 loại item, mạng/hồi sinh, khiên và cấu hình trận](docs/EXTENDED_ITEMS_LIVES.md).
Bom mặc định range 1; 3 mạng/người; drop từ block 75%, không có item đầu trận.

Movement mặc định: ô 32×32, hitbox 24×24 căn giữa, tốc độ 80 px/s, giày +8 (trần 144).
[Cấu hình và kiểm chứng logic ô](docs/MOVEMENT_BALANCE.md).

Map có lưới rõ và viền xanh đánh dấu ô nhân vật; giữ di chuyển mượt, bom đặt theo ô chứa tâm.
Xem [quy tắc ô và đặt bom](docs/GRID_BOMB_PLACEMENT.md).

Map gameplay chỉnh tại `server/game/map/DefaultArenaMap.java`: tường đá cố định và thùng gỗ
phân bố đối xứng, bảo đảm vùng thoát bom ở bốn góc. [Bố cục và cách sửa](docs/DEFAULT_ARENA_MAP.md).

Bàn giao nhiệm vụ 10 (22/10/2026): [báo cáo/checklist](docs/TASK10_HANDOVER.md),
[số đo và CSV thực tế](docs/NETWORK_MEASUREMENTS.md), [chạy LAN](docs/LAN_GUIDE.md),
[đóng gói](docs/PACKAGING.md), [báo cáo kỹ thuật](docs/TECHNICAL_REPORT.md),
[kịch bản demo](docs/DEMO_SCRIPT.md). Đã chạy 112 test và đo TCP loopback 1/2/4 client;
nghiệm thu GUI và LAN nhiều máy, soak dài hạn vẫn cần nhóm thực hiện.
Reconnect lobby đã bật mặc định: giữ chỗ 30 giây, trận tiếp tục; hết hạn chỉ loại người mất mạng.

Build bản chạy không cần Maven; chạy JUnit cần Maven. Java 25+ (build cần JDK):

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\test.cmd
cmd /c scripts\run-benchmark.cmd --local --mode suite --seconds 5 --warmup 1 --csv target\network-results.csv
cmd /c scripts\package.cmd
```

JAR: `target/bomb-it-multiplayer.jar`; ZIP runtime và SHA256: `target/release/`.
Giải nén ZIP trên máy nhận có Java 25+, rồi chạy `scripts\run-server.cmd 8888`
và `scripts\run-client.cmd SERVER_IP 8888 TEN`. Không cần Maven/JDK trên máy chỉ chạy.
ZIP không chứa source hoặc Java runtime; chuyển source repository riêng nếu cần build lại.

Project môn Lập trình Mạng dùng Java 25+, Swing và TCP. Đã ghép lobby, movement, bom/item multiplayer
với renderer TV3, damage/chết và kết quả trận tự động. Maven dùng để chạy test;
script demo có thể build production bằng javac mà không cần Maven.

## Bắt đầu

Độ ổn định: [báo cáo nhiệm vụ 9](docs/TASK9_STABILITY.md) có giới hạn queue/rate,
deadline và kết quả 108 test cùng stress cục bộ. Reconnect hiện đã bật;
xem [cơ chế khôi phục kết nối](docs/RECONNECT_ASSESSMENT.md).

M2 có Connect/Lobby, tạo/join/leave phòng, ready/start, chat, quyền host và chơi lại. Xem
[docs/M2_LOBBY_LIFECYCLE.md](docs/M2_LOBBY_LIFECYCLE.md) để chạy, xem test thực tế và checklist GUI/LAN còn lại.

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\run-server.cmd 8888
```

Mở hai terminal khác, mỗi terminal chạy một client:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Chinh
```

Đổi tên client còn lại thành Mai. Bấm Connect; một người Create, người kia chọn phòng và Join.
Khách Ready, host Start; dùng phím mũi tên di chuyển và Space đặt bom. Item tự nhặt khi bước vào ô;
HUD hiện quota/range/speed và thời gian còn lại. Hết trận tự mở tab Kết quả trong lobby.
Ready/Start để chơi lại; host End round để hủy lượt hiện tại;
Leave room để về danh sách phòng. Chủ động rời phòng hủy lượt; mất kết nối giữ chỗ 30 giây,
client tự kết nối lại khi ứng dụng còn mở. Hết hạn chỉ loại người mất mạng.

M1 vẫn có tại [docs/M1_MOVEMENT_DEMO.md](docs/M1_MOVEMENT_DEMO.md): server thêm số người `2`,
client thêm `--demo` sau tên. `--headless` dành cho smoke M1.

Tiến độ server transport: [báo cáo nhiệm vụ 3](docs/SERVER_TRANSPORT_STATUS.md).
Sau `mvn test`, có thể chạy `java -cp target/classes com.bombit.server.network.Server 8888`.
Server transport thuần yêu cầu CONNECT protocol 2 trước PING và chỉ nối CONNECT/PING/DISCONNECT.
Để dùng lobby/movement, dùng ServerApplication/run-server.cmd ở trên.
Công cụ manual-client legacy chưa được nâng cấp handshake.

1. Giải nén project và mở thư mục `BombItMultiplayer-Structure` bằng VS Code/IntelliJ.
2. Đảm bảo máy có JDK 25+ và Maven (hoặc Maven extension trong VS Code).
3. Chạy kiểm tra build bằng `mvn test` từ thư mục có `pom.xml`.
4. Tạo branch theo module trước khi code: `feature/tv1-network`, `feature/tv2-movement`, `feature/tv3-bomb-items`, `feature/tv4-protocol-metrics`.

## Chủ sở hữu module

Tiến độ hiện tại: [item, chết và kết quả trận](docs/TASK8_ITEMS_RESULTS.md).
Luật mặc định: drop 75%, 3 mạng/người, trận 180 giây, còn một người thắng, cùng hết mạng/hết giờ hòa;
disconnect giữ chỗ 30 giây rồi loại riêng người mất mạng. TCP và bảng kết quả offscreen đã kiểm thử; nghiệm thu Swing trực tiếp/LAN
vẫn cần nhóm xác nhận; stress hữu hạn đã chạy ở nhiệm vụ 9. Profile M1 vẫn chỉ di chuyển.

| Thành viên | Thư mục chính | Trách nhiệm |
|---|---|---|
| TV1 (Mai) | `server/network/`, `server/session/`, `server/room/` | TCP lifecycle, client connection, session, room/lobby, chat, heartbeat, disconnect |
| TV2 | `server/game/core/`, `server/game/movement/`, `client/game/movement/` | Game loop, GameState, PlayerState, collision/movement, snapshot, prediction/interpolation |
| TV3 (Duy) | `server/game/bomb/`, `server/game/item/`, `client/game/bomb/`, `client/game/item/` | Đặt bom, timer, explosion, item, gameplay events và phần hiển thị liên quan |
| TV4 (Giang) | `common/protocol/`, `server/dispatch/`, `client/network/`, `metrics/`, `docs/protocol.md` | Packet format, encode/decode, routing contract, network metrics, test/evaluation tổng hợp |

`server/app/` và `client/app/` là điểm ghép hệ thống. Một người tích hợp được nhóm chỉ định, nhưng không để nhiều thành viên tự sửa bootstrap song song.

## Luật để tránh conflict

1. **TV4 sở hữu `common/protocol/`**. Các TV khác đề xuất message/field qua `docs/protocol.md`; không tự sửa packet class/enum.
2. **TV2 sở hữu `server/game/core/`**. TV3 nêu nhu cầu API qua interface/PR; không tự thêm/xóa field trong `GameState`.
3. **TV1 sở hữu connection và room**. Các module không tự tạo Socket hoặc tự broadcast bằng Socket riêng.
4. **TV3 chỉ xử lý luật bomb/item**. TV3 phát event/kết quả qua API đã thống nhất; không tự viết Game Loop.
5. Chỉ một người chịu trách nhiệm tích hợp các file bootstrap (`ServerApplication`, `ClientApplication`, cấu hình khởi tạo).
6. Mỗi PR nên tập trung một module; tránh sửa format toàn project hoặc nhiều package không liên quan trong cùng PR.
7. Không commit `target/`, file IDE, class build hoặc file cá nhân.

## Luồng kiến trúc dự kiến

`ClientNetwork -> PacketDecoder -> Server Dispatcher -> Handler / Game Command -> Game Loop -> GameState -> Event/Snapshot -> PacketEncoder -> Room Broadcast`

Các kết nối/handler không sửa `GameState` tùy tiện từ nhiều thread. Các thay đổi gameplay nên được chuyển về luồng Game Loop qua command queue hoặc cơ chế đồng bộ được nhóm thống nhất.

## Ghi chú về Server Foundation (TV1 - Mai)

- TCP server lifecycle, graceful shutdown và thread pool.
- Quản lý Session và Room lifecycle; reconnect ứng dụng chưa bật, xem đánh giá phạm vi riêng.
- Room broadcast, Chat handler, Heartbeat.
- Port mặc định: 8888 (`server.network.Server`).

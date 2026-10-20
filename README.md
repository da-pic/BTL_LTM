# Bomb It Multiplayer — Java project structure

Bộ khung thư mục cho project môn Lập trình Mạng. Dùng Java 17+ + Maven; chưa khóa thư viện giao diện (JavaFX/Swing/LibGDX) và chưa cài luật gameplay cụ thể.

## Bắt đầu

1. Giải nén project và mở thư mục `BombItMultiplayer-Structure` bằng VS Code/IntelliJ.
2. Đảm bảo máy có JDK 17+ và Maven (hoặc Maven extension trong VS Code).
3. Chạy kiểm tra build bằng `mvn test` từ thư mục có `pom.xml`.
4. Tạo branch theo module trước khi code: `feature/tv1-network`, `feature/tv2-movement`, `feature/tv3-bomb-items`, `feature/tv4-protocol-metrics`.

## Chủ sở hữu module

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
- Quản lý Session, Reconnect token auth, Room lifecycle.
- Room broadcast, Chat handler, Heartbeat.
- Port mặc định: 8888 (`server.network.Server`).

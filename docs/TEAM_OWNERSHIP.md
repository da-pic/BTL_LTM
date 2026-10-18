# Phân quyền sở hữu module

## TV1 — Network / Session / Room
- `server/network`: server socket, client handler, connection lifecycle.
- `server/session`: player session, trạng thái kết nối.
- `server/room`: room, lobby, chat, heartbeat integration nếu nhóm đặt heartbeat tại đây.
- Không xử lý movement/bomb và không tự định nghĩa binary packet.

## TV2 — Game Core / Movement
- `server/game/core`: `GameState`, bản đồ, player state, game loop.
- `server/game/movement`: nhận/xử lý lệnh di chuyển, collision, tạo snapshot.
- `client/game/movement`: input, prediction, interpolation/reconciliation nếu triển khai.
- Là chủ sở hữu API thay đổi GameState; trao đổi TV3 trước khi thêm field/method.

## TV3 — Bomb / Explosion / Item
- `server/game/bomb`: bomb, đặt bom, thời điểm nổ, vùng nổ, phá block, sát thương theo luật game.
- `server/game/item`: sinh và nhặt vật phẩm.
- `server/game/event`: event gameplay phát sinh từ bomb/item (nếu nhóm dùng package này).
- `client/game/bomb` và `client/game/item`: biểu diễn/animation phía client.
- Không sửa packet schema hoặc game loop trực tiếp; dùng API đã thống nhất.

## TV4 — Protocol / Dispatcher / Metrics
- `common/protocol`: danh sách packet type, frame format, encoder/decoder, lỗi protocol.
- `server/dispatch`: định tuyến message đến handler tương ứng; chỉ một nơi quyết định routing.
- `client/network`: send/receive và xử lý packet phía client.
- `metrics`: latency, throughput, packet rate và kết quả benchmark.
- `docs/protocol.md`: đặc tả message được mọi người cùng duyệt.

## Quy trình thay đổi API chung
1. Người cần thay đổi tạo issue hoặc PR mô tả lý do và đề xuất API.
2. Chủ sở hữu module liên quan duyệt.
3. Cập nhật tài liệu protocol/API trước hoặc cùng PR.
4. Merge thay đổi tương thích; sau đó mới merge module phụ thuộc.

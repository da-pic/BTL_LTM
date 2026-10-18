# Network Protocol Specification (DRAFT)

TV4 duy trì tài liệu này. Các message bên dưới là danh sách ban đầu để thảo luận, **chưa phải schema đã chốt**.

## Quy tắc framing cần quyết định
- Transport: TCP (dự kiến).
- Frame: độ dài + message type + payload (đang đề xuất).
- Byte order: phải thống nhất (khuyến nghị big-endian nếu dùng `ByteBuffer` mặc định).
- String: UTF-8.
- Giới hạn payload tối đa: cần xác định để tránh cấp phát bộ nhớ quá lớn.
- Chốt định dạng bằng ví dụ byte-level trước khi các module gửi/nhận.

## Message dự kiến

| Message | Hướng | Dữ liệu gợi ý | Chủ trì nghiệp vụ |
|---|---|---|---|
| `CREATE_ROOM` | C→S | cấu hình phòng nếu có | TV1 |
| `JOIN_ROOM` | C→S | room id | TV1 |
| `LEAVE_ROOM` | C→S | room id | TV1 |
| `CHAT` | C→S, S→C | nội dung, người gửi | TV1 |
| `READY` | C→S, S→C | trạng thái ready | TV1 |
| `MOVE` | C→S | hướng hoặc input | TV2 |
| `GAME_SNAPSHOT` | S→C | trạng thái player cần render | TV2 |
| `PLANT_BOMB` | C→S | vị trí/input theo luật đã chốt | TV3 |
| `BOMB_PLACED` | S→C | bomb id, vị trí, thời gian nổ | TV3 |
| `BOMB_EXPLODE` | S→C | bomb id, danh sách ô nổ | TV3 |
| `BLOCK_DESTROYED` | S→C | vị trí block | TV3 |
| `ITEM_SPAWN` | S→C | item id/type/position | TV3 |
| `ITEM_PICKUP` | C→S hoặc chỉ event S→C tùy thiết kế | player/item id | TV3 |
| `PLAYER_DIE` | S→C | player id, lý do nếu cần | TV2/TV3 cần thống nhất |
| `PING` / `PONG` | hai chiều | nonce/timestamp nếu cần | TV1/TV4 |
| `ERROR` | S→C | code, thông báo an toàn | TV4 + module nghiệp vụ |

## Quy tắc
- Server authoritative: Client gửi ý định; Server xác thực và quyết định state cuối cùng.
- Các event gameplay gửi khi phát sinh; snapshot movement gửi theo nhịp đã thống nhất.
- TCP cung cấp luồng byte, không giữ ranh giới message; decoder phải xử lý packet bị chia thành nhiều lần đọc hoặc nhiều packet trong một lần đọc.
- Không xem `ITEM_PICKUP`/`BOMB_EXPLODE` là packet hoàn chỉnh cho tới khi cả nhóm chốt field, kiểu dữ liệu và thứ tự byte.

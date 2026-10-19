# Bomb It Multiplayer Protocol

## Packet framing

Mỗi message đi qua TCP theo frame `[length: int32][type: int16][payload: UTF-8 JSON]`.
`length` tính số byte của `type + payload`, không tính 4 byte length. Các số nguyên trong
header dùng big-endian. Decoder phải đọc đủ body theo length vì TCP là byte stream và có thể
chia hoặc gộp nhiều packet. Length hợp lệ từ 2 byte đến 1 MiB.

## Quy ước dữ liệu

- `playerId`, `ownerPlayerId`, `killerPlayerId`, `winnerPlayerId`, `roomId`: UUID dạng JSON string.
- `bombId`, `snapshotSeq`, `lastSeq`, `requestId`: số nguyên 64-bit (`long` Java), JSON number.
- `int`, `boolean`, `string`, `array`, `object` giữ kiểu JSON tương ứng.
- Trường ghi `nullable` có thể là JSON `null`; trường UUID không được tin từ client nếu server có thể lấy từ session đã xác thực.
- Các trường `players`, `bombs`, `items`, `affectedCells` là array các object; mọi tọa độ lưới dùng `x`, `y` kiểu int.

## Message types

| Type | Tên | Hướng | Payload chính | Xử lý |
| --- | --- | --- | --- | --- |
| `0x0001` | `CONNECT` | Client → Server | `playerName:string`, `protocolVersion:int` | Server kiểm tra tên và phiên bản; trả `CONNECT_ACK` hoặc `ERROR`. |
| `0x0002` | `CONNECT_ACK` | Server → Client | `playerId:UUID`, `serverVersion:string` | Client lưu ID do server cấp. |
| `0x0010` | `CREATE_ROOM` | Client → Server | `roomName:string`, `maxPlayers:int`, `mode:string` | Server tạo phòng và trả `ROOM_STATE`. |
| `0x0011` | `JOIN_ROOM` | Client → Server | `roomId:UUID` | Server kiểm tra phòng, chỗ trống và trạng thái trận. |
| `0x0012` | `ROOM_STATE` | Server → Client/Room | `roomId:UUID`, `players:array`, `maxPlayers:int`, `roomStatus:string` | Client cập nhật danh sách người chơi và trạng thái phòng. |
| `0x0013` | `READY` | Client → Server | `roomId:UUID`, `ready:boolean` | Server cập nhật trạng thái và broadcast `ROOM_STATE`. |
| `0x0014` | `START_GAME` | Client → Server | `roomId:UUID` | Server kiểm tra host và điều kiện bắt đầu. |
| `0x0015` | `GAME_STARTED` | Server → Room | `roomId:UUID`, `mapId:string`, `timeLimitSec:int`, `players:array` | Client khởi tạo trận đấu. |
| `0x0016` | `LEAVE_ROOM` | Client → Server | `roomId:UUID` | Rời phòng về lobby; không nhất thiết đóng socket. |
| `0x0017` | `CHAT` | Client → Server → Room | `roomId:UUID`, `message:string` | Server lấy người gửi từ session rồi broadcast cho room. Mã này được bổ sung theo yêu cầu chat chung của project. |
| `0x0020` | `MOVE` | Client → Server | `direction:string`, `inputSeq:int` | Server xác thực input, va chạm và tự cập nhật vị trí. |
| `0x0021` | `PLANT_BOMB` | Client → Server | `requestId:long` | Server kiểm tra người chơi; nếu hợp lệ sẽ phát `BOMB_PLANTED`. |
| `0x0022` | `BOMB_PLANTED` | Server → Room | `bombId:long`, `ownerPlayerId:UUID`, `x:int`, `y:int`, `fuseMs:int`, `range:int` | Client hiển thị bom; server giữ quyền quyết định timer. |
| `0x0023` | `BOMB_EXPLODE` | Server → Room | `bombId:long`, `x:int`, `y:int`, `affectedCells:array` | Client hiển thị vùng nổ và cập nhật hiệu ứng bản đồ. |
| `0x0024` | `ITEM_SPAWN` | Server → Room | `itemId:int`, `itemType:string`, `x:int`, `y:int` | Client hiển thị item. |
| `0x0025` | `ITEM_PICKED_UP` | Server → Room | `itemId:int`, `playerId:UUID`, `itemType:string` | Client xóa item và cập nhật chỉ số người chơi. |
| `0x0026` | `PLAYER_DIE` | Server → Room | `playerId:UUID`, `cause:string`, `killerPlayerId:UUID|null` | Server cập nhật trạng thái sống/chết; client hiển thị kết quả. |
| `0x0030` | `GAME_SNAPSHOT` | Server → Room | `snapshotSeq:long`, `remainingTimeSec:int`, `players:array`, `bombs:array`, `items:array` | Client cập nhật trạng thái trận authoritative. |
| `0x0031` | `GAME_OVER` | Server → Room | `winnerPlayerId:UUID|null`, `reason:string`, `rankings:array` | Client kết thúc trận và hiển thị kết quả. |
| `0x0032` | `PLAYER_DISCONNECTED` | Server → Room | `playerId:UUID`, `reason:string` | Server thông báo người chơi đã rời/mất kết nối; ví dụ reason `USER_QUIT`, `NETWORK_LOST`, `TIMEOUT`. |
| `0x0033` | `FULL_STATE_SYNC` | Server → Client | `mapData:object`, `players:array`, `bombs:array`, `items:array` | Client thay trạng thái cũ bằng toàn bộ trạng thái để tiếp tục sau reconnect. |
| `0x0040` | `PING` | Client → Server | `pingId:int` | Client ghi nanoTime tại thời điểm gửi. |
| `0x0041` | `PONG` | Server → Client | `pingId:int` | Server trả lại đúng pingId để client tính RTT. |
| `0x0042` | `DISCONNECT` | Client → Server | `reason:string` | Thoát chủ động; dùng `reason:"USER_QUIT"`. Server giải phóng session và broadcast `PLAYER_DISCONNECTED`. |
| `0x0043` | `RECONNECT` | Client → Server | `playerId:UUID` | Server tìm session cũ trong thời hạn 15 giây; nếu còn, gắn socket mới và gửi `FULL_STATE_SYNC`. |
| `0x0044` | `NETWORK_LOST` | Client → Server (best-effort) | `playerId:UUID`, `lastSeq:long`, `reason:string` | Client chỉ gửi được nếu socket còn truyền được. Với mất mạng đột ngột, server phải tự phát hiện EOF/socket error/heartbeat timeout, giữ session 15 giây và broadcast `PLAYER_DISCONNECTED` với reason `NETWORK_LOST`. |
| `0x00FF` | `ERROR` | Server → Client | `errorCode:string`, `message:string`, `requestId:long|null` | Client ghi log/hiển thị lỗi; không tự thay đổi trạng thái authoritative. |

`NETWORK_LOST` là tín hiệu best-effort, không phải điều kiện để server phát hiện disconnect: client không thể gửi packet qua kết nối đã đứt. `DISCONNECT` được dùng khi client chủ động thoát; `PLAYER_DISCONNECTED` là thông báo của server tới các peer sau khi đã xác định người chơi rời hoặc mất kết nối.

## Payload examples

```json
{"playerName":"Giang","protocolVersion":1}
{"playerId":"a3d9a2f1-1b7d-4b30-8c18-21a88c4c5e10","serverVersion":"1.0"}
{"roomId":"135a9c7e-650a-40c0-96ac-5e8080a6d461","ready":true}
{"direction":"RIGHT","inputSeq":12}
{"requestId":1001}
{"bombId":9001,"ownerPlayerId":"a3d9a2f1-1b7d-4b30-8c18-21a88c4c5e10","x":4,"y":7,"fuseMs":3000,"range":2}
{"pingId":42}
{"playerId":"a3d9a2f1-1b7d-4b30-8c18-21a88c4c5e10","lastSeq":128,"reason":"NETWORK_UNAVAILABLE"}
```

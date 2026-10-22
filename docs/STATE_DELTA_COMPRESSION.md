# State Delta Compression — TV2

## Phần đã triển khai

- GameLoop giữ 20 tick/giây, full state đầu trận và mỗi 100 tick; không gửi delta rỗng.
- StateDeltaBuilder so sánh map, player và entity với baseline đã commit vào outbound queue.
- EntityData là dữ liệu bất biến cho bomb/item: kind + id là khóa; tọa độ dùng tile.
  `variant` là range của bomb hoặc mã item do nhóm thống nhất; `expiresAtMillis` là
  thời điểm nổ theo đồng hồ server, không phải countdown giảm mỗi tick.
- GameState.putEntity/removeEntity là API xuất trạng thái cho TV3; gọi trên GameLoop thread.
  Chưa tự nối các API này với BombSystem/ItemSystem và chưa triển khai BombWorld/ItemWorld.
- ClientWorldState áp dụng full/delta, bỏ update cũ, từ chối sai baseline trước khi sửa state.
- GameLoop.requestFullState() an toàn để network worker yêu cầu phục hồi.
- GameDebugWindow dùng codec và ClientWorldState chung; vẫn là demo local, chưa qua TCP.

## Định dạng tích hợp đề xuất v2

Không thay đổi Packet/Encoder/Decoder dùng chung. SnapshotPacketCodec tạo GAME_SNAPSHOT
với JSON envelope `{"snapshotVersion":2,"data":"<base64>"}`. Base64 chứa binary
big-endian: loại update byte (0 full/1 delta), tick int64, baseTick int64,
width/height/tileSize int32, matchState byte, matchStateChanged boolean;
sau đó các collection có count int32: fullMap, tileChanges, players,
removedPlayerIds, entityChanges, removedEntityKeys.

Tile là enum byte; tile change gồm x/y int32 + enum byte. Player gồm id int32,
x/y/width/height/speed float64, direction byte, moving/alive boolean, lastProcessedInput int64.
Entity gồm kind byte, id/x/y int32, ownerId writeUTF, variant int32,
expiresAtMillis int64. Removed entity key dùng writeUTF (`BOMB:1`, `ITEM:1`).
writeUTF là Java modified UTF-8, có length uint16. Enum dùng thứ tự khai báo hiện tại;
đổi thứ tự hoặc layout phải tăng phiên bản codec.

Đây là đề xuất để TV4 duyệt, không phải thay đổi schema chung đã được nhóm chốt.
Envelope hiện yêu cầu đúng dạng canonical do encode tạo; không phải JSON parser tổng quát.
Base64 có overhead, được tính vào benchmark. Có thể tối ưu transport sau khi TV4 thống nhất.

## Hợp đồng tích hợp mạng

Mỗi room có GameRuntime/GameLoop/baseline riêng. Worker đọc runtime.pollUpdate(), encode,
rồi broadcast theo room qua hệ thống TV1. Client listener decode packet trên network
thread rồi chuyển update sang một UI/simulation thread để apply và render.

Khi apply trả NEED_FULL_STATE, client cần báo server bằng message resync do TV4 chốt;
server gọi requestFullState(). Sau reconnect phải nhận full trước delta.
Nếu network worker bỏ update đã commit hoặc queue một client đầy, chuỗi delta của client
đó bị đứt: cần full state. Không bỏ update giữa chuỗi rồi giả định baseline vẫn đúng.
Commit queue chưa phải xác nhận nhận từ client. Full định kỳ hỗ trợ phục hồi nhưng
không thay thế xử lý lỗi broadcast. Không có cơ chế ACK/resync qua TCP trong thay đổi này.

## Chạy kiểm tra và đo

Từ thư mục project, chạy `cmd /c scripts\check-tv2.cmd` với JDK 25+.
Một script duy nhất biên dịch production, debug UI và chạy regression không cần Maven.
Khi có Maven, `mvn test` cũng chạy regression qua DeltaCompressionTest.
Kết quả từng tick ghi vào `target/delta-compression-results.csv`; dòng cuối là tổng kết.
Mở debug UI sau khi biên dịch bằng:
`java -cp target/tv2-check com.bombit.client.debug.GameDebugWindow`.

Benchmark mô phỏng 200 tick = 10 giây ở 20 tick/giây, không chờ thời gian thực.
Map 17×15, một player di chuyển trong 80 tick đầu; có thêm/xóa bom và thêm item.
So sánh cùng world mỗi tick: full mỗi tick với delta + full tại tick 1/100/200.
Đo PacketEncoder.encode(packet).length, gồm header 6 byte và JSON/Base64;
chưa tính TCP/IP overhead, latency, CPU hoặc traffic thực qua socket.

Kết quả v2: full 110076 byte, delta 16411 byte, giảm 85,09%; 83/200 packet.
Đây là kết quả của kịch bản xác định này, không phải mức giảm đảm bảo cho mọi trận.
Regression kiểm tra thêm/xóa entity, player removal, baseline chưa commit,
delta bị mất, full phục hồi, update cũ, envelope sai và so sánh replica với full mỗi tick.

Xem [TV2_CONTRACT_MIGRATION.md](TV2_CONTRACT_MIGRATION.md) để ghép runtime chuẩn,
command và GameplayExtension. Phần còn lại là tích hợp lobby/router/bootstrap của nhóm;
TV3 cung cấp gameplay extension, TV4 tiếp quản wire format.
Benchmark đổi tọa độ trực tiếp, chưa tăng ACK mỗi mẫu như client thật; không coi số packet
ở giai đoạn đứng yên là số liệu nghiệm thu mạng thật.

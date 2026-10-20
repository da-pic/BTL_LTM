4. Thành viên 4 — Protocol, Message Router, Network Metrics & Evaluation (Giang)
Thiết kế giao thức và đánh giá hệ thống
Trọng tâm Network Programming:
Network Protocol.
Data Format.
Serialization.
Message Routing.
Latency.
Throughput.
Reliability.
Testing & Evaluation.
Công việc
1. Thiết kế Protocol
Định nghĩa toàn bộ message giữa Client và Server.
Quy ước chiều gửi: C → S là Client gửi Server; S → C là Server gửi một Client; S → Room là Server gửi cho những Client trong phòng liên quan.
STT
TYPE
Message
Chiều gửi
Mục đích
Payload — các trường dữ liệu
Xử lý và phản hồi
1
0x0001
CONNECT
C → S
Yêu cầu kết nối vào game.
playerName: string, protocolVersion: int
Server kiểm tra dữ liệu và phiên bản giao thức; phản hồi CONNECT_ACK hoặc ERROR.
2
0x0002
CONNECT_ACK
S → C
Xác nhận kết nối thành công.
playerId: int, serverVersion: string
Client lưu ID người chơi do Server cấp và chuyển sang trạng thái đã kết nối.
3
0x0010
CREATE_ROOM
C → S
Tạo phòng chơi mới.
roomName: string, maxPlayers: int, mode: string
Server tạo phòng nếu dữ liệu hợp lệ; trả về ROOM_STATE chứa thông tin phòng.
4
0x0011
JOIN_ROOM
C → S
Tham gia phòng đã có.
roomId: int
Server kiểm tra phòng tồn tại, còn chỗ và chưa bắt đầu trận; trả về ROOM_STATE hoặc ERROR.
5
0x0012
ROOM_STATE
S → C / S → Room
Gửi trạng thái phòng.
roomId: int, players: array, maxPlayers: int, roomStatus: string
Client cập nhật danh sách người chơi, trạng thái sẵn sàng và trạng thái phòng.
6
0x0013
READY
C → S
Người chơi báo sẵn sàng hoặc bỏ sẵn sàng.
roomId: int, ready: boolean
Server kiểm tra người chơi thuộc phòng và cập nhật trạng thái; phát ROOM_STATE cho phòng.
7
0x0014
START_GAME
C → S
Yêu cầu bắt đầu trận đấu.
roomId: int
Server kiểm tra quyền bắt đầu và điều kiện vào trận; nếu hợp lệ, khởi tạo trận và phát GAME_STARTED.
8
0x0015
GAME_STARTED
S → Room
Thông báo trận đấu bắt đầu.
roomId: int, mapId: string, timeLimitSec: int, players: array
Client tải bản đồ, khởi tạo nhân vật và bắt đầu hiển thị trận đấu.
9
0x0020
MOVE
C → S
Yêu cầu di chuyển nhân vật.
direction: string, inputSeq: int
Server kiểm tra hướng đi, trạng thái người chơi và vật cản; cập nhật vị trí hợp lệ.
10
0x0021
PLANT_BOMB
C → S
Yêu cầu đặt bom.
requestId: long
Server kiểm tra người chơi còn sống, đang trong trận và có thể đặt bom; hợp lệ thì tạo bom và phát BOMB_PLANTED.
11
0x0022
BOMB_PLANTED
S → Room
Thông báo bom đã được đặt.
bombId: int, ownerPlayerId: int, x: int, y: int, fuseMs: int, range: int
Client hiển thị bom tại đúng vị trí và theo dõi thời gian phát nổ do Server quản lý.
12
0x0023
BOMB_EXPLODE
S → Room
Thông báo bom phát nổ.
bombId: int, x: int, y: int, affectedCells: array
Server xác định vùng nổ và đối tượng bị ảnh hưởng; Client hiển thị hiệu ứng và cập nhật bản đồ theo sự kiện nhận được.
13
0x0024
ITEM_SPAWN
S → Room
Thông báo vật phẩm xuất hiện.
itemId: int, itemType: string, x: int, y: int
Client hiển thị vật phẩm tại vị trí tương ứng trên bản đồ.
14
0x0025
ITEM_PICKED_UP
S → Room
Thông báo người chơi đã nhặt vật phẩm.
itemId: int, playerId: int, itemType: string
Server xác nhận vật phẩm được nhặt và áp dụng hiệu ứng; Client xóa vật phẩm khỏi bản đồ, cập nhật chỉ số người chơi.
15
0x0026
PLAYER_DIE
S → Room
Thông báo người chơi bị hạ.
playerId: int, cause: string, killerPlayerId: int (có thể null)
Server cập nhật trạng thái sống/chết; Client hiển thị hiệu ứng và cập nhật số người chơi còn lại.
16
0x0030
GAME_SNAPSHOT
S → Room
Đồng bộ trạng thái hiện tại của trận đấu.
snapshotSeq: long, remainingTimeSec: int, players: array, bombs: array, items: array
Client cập nhật vị trí nhân vật, bom, vật phẩm, thời gian và các trạng thái cần đồng bộ.
17
0x0031
GAME_OVER
S → Room
Thông báo trận đấu kết thúc.
winnerPlayerId: int (có thể null), reason: string, rankings: array
Client dừng trận đấu, hiển thị người thắng và kết quả.
18
0x0032
PLAYER_DISCONNECTED
S → Room
Thông báo người chơi mất kết nối.
playerId: int, reason: string
Server cập nhật kết nối và trạng thái phòng/trận; các Client còn lại cập nhật danh sách người chơi.
19
0x0040
PING
C → S
Kiểm tra kết nối và đo độ trễ.
pingId: int
Client ghi thời điểm gửi; Server trả lời PONG với cùng pingId.
20
0x0041
PONG
S → C
Phản hồi yêu cầu PING.
pingId: int
Client ghép phản hồi với yêu cầu đã gửi và tính RTT để hiển thị ping.
21
0x0042
DISCONNECT
C → S
Chủ động ngắt kết nối hoặc rời phiên chơi.
reason: string
Server giải phóng tài nguyên, cập nhật phòng/trận và thông báo người chơi rời đi khi cần.
22
0x00FF
ERROR
S → C
Báo yêu cầu không hợp lệ hoặc không thể xử lý.
errorCode: string, message: string, requestId: long (có thể null)
Client ghi log hoặc hiển thị lỗi; không tự thay đổi trạng thái game chính thức.
23
0x0016 
LEAVE_ROOM 
C → S 
Người chơi yêu cầu rời phòng chơi.
roomId: string 
Server rút người chơi khỏi phòng, trả trạng thái về Lobby, tự động chuyển Host/hủy phòng nếu trống. 
24
0x0033 
FULL_STATE_SYNC 
S → C 
Đồng bộ toàn bộ bản đồ/game cho Client Reconnect thành công. 
mapData: object, players: array, bombs: array, items: array 
Client xóa dữ liệu cũ, cập nhật toàn bộ trạng thái bàn cờ hiện tại để tiếp tục ván đấu. 
25
0x0043 
RECONNECT 
C → S 
Yêu cầu tái kết nối sau khi mất Wi-Fi/rớt mạng. 
playerId: string 
Server kiểm tra phiên cũ trên RAM; nếu còn trong 15s thì gắn Socket mới vào Session cũ và trả lời FULL_STATE_SYNC. 

Trong cột Payload, các kiểu string, int, long, boolean, array và object là kiểu dữ liệu logic của JSON.
2. Packet Structure

Packet là đơn vị dữ liệu mà chương trình của bạn quy ước để đóng gói một message trước khi gửi qua mạng. 
Thiết kế format thống nhất:
+--------+--------+----------+
| LENGTH |  TYPE  | PAYLOAD  |
+--------+--------+----------+

Thành phần
Quy định
LENGTH
Số nguyên 32 bit, 4 byte; tính tổng số byte của TYPE và PAYLOAD, không tính chính LENGTH.
TYPE
Số nguyên 16 bit, 2 byte; dùng mã số cố định để xác định loại message.
PAYLOAD
Dữ liệu JSON được chuyển thành byte theo UTF-8.
Byte order
Big-endian cho LENGTH và TYPE.
Độ dài tối đa
Đề xuất LENGTH không vượt quá 1 MiB (1.048.576 byte).
Giao thức truyền
TCP



Dữ liệu
Kích thước thông dụng
Lưu ý
byte
1 byte
Mã nhỏ, cờ trạng thái
short
2 bytes
Mã loại message nếu đủ phạm vi
int
4 bytes
ID, tọa độ, độ dài
long
8 bytes
Timestamp hoặc số thứ tự lớn
float
4 bytes
Giá trị số thực
String
Thay đổi
Cần quy ước UTF-8 và độ dài


Xác định:
Kiểu dữ liệu.
Byte order.
Encoding.
Độ dài packet.
Cách phân biệt các message.
Ví dụ:
int32  → 4 bytes
short  → 2 bytes
byte   → 1 byte
String → UTF-8
3. Encoder / Decoder
-Encoder chuyển một message trong chương trình thành mảng byte để gửi qua Socket. 
Vd: MoveCommand(direction = LEFT, x = 5, y = 8)
                 |
                 v
           PacketEncoder
                 |
                 v
       [13, 0, 0, 0, ...]
                 |
                 v
             TCP Socket
Mảng byte phía trên chỉ minh họa dữ liệu nhị phân; giá trị thực tế phụ thuộc định dạng bạn thiết kế.
Trong Java, Encoder thường sử dụng ByteBuffer, DataOutputStream hoặc các phương pháp chuyển đổi byte khác.
-Decoder ngược lại
TCP Socket -> Các byte nhận được -> PacketDecoder -> Packet(type = MOVE, payload = ...) -> MessageRouter

Xây dựng:
PacketEncoder
PacketDecoder
Ví dụ:
Object / Command
      ↓
Encoder
      ↓
byte[]
      ↓
TCP
      ↓
byte[]
      ↓
Decoder
      ↓
Packet
4. Message Router / Dispatcher
Router giống như một nhân viên phân loại thư. Khi nhận được một packet, nó xem packet thuộc loại nào rồi chuyển đến đúng module xử lý. 
Nhận packet đã được Decoder giải mã.
Xác định loại message.
Chuyển đến Handler tương ứng.
Xử lý loại message chưa đăng ký.
Ghi log lỗi cần thiết.
Đảm bảo message đi đến đúng phía và đúng ngữ cảnh.
Packet
   ↓
MessageRouter
   ├── MOVE → MovementHandler
   ├── PLANT_BOMB → BombHandler
   ├── CHAT → ChatHandler
   ├── PONG → HeartbeatHandler
   └── ...
Mục tiêu là tránh một if/else khổng lồ trong ClientHandler.
Router: quyết định packet phải đi đến đâu.
Handler: thực hiện xử lý nghiệp vụ của message.
5. Client Network Framework
Xây dựng lớp mạng dùng chung cho Client:
ClientNetwork
 ├── connect()
 ├── send()
 ├── receive()
 └── disconnect()
TV2 và TV3 sử dụng framework này để không phải tự viết Socket networking riêng.
6. Latency Measurement
- Đo RTT (Round-Trip Time) - thời gian một thông điệp đi từ Client đến Server và phản hồi quay lại Client :
Client → PING → Server → PONG → Client
Tính:
RTT = responseTime - sendTime

Trong Java, nên dùng System.nanoTime() để đo khoảng thời gian trôi qua, thay vì dùng thời gian lịch của hệ thống 
Hiển thị:
Ping: 25 ms
- Nên có lớp LatencyMonitor đảm nhiệm:
Ghi thời điểm gửi PING.
Nhận PONG tương ứng.
Tính RTT.
Lưu các mẫu đo.
Tính RTT trung bình, nhỏ nhất, lớn nhất.
Tính median hoặc p95 nếu cần đánh giá sâu hơn.
Cung cấp kết quả cho giao diện hoặc báo cáo.
RTT trung bình:

RTT=i=1nRTTin
Trong đó n là số lần đo thành công.
Bạn cũng nên tính tỷ lệ PING bị timeout. Không được bỏ các lần mất phản hồi mà không ghi nhận, vì như vậy kết quả sẽ trông tốt hơn thực tế.
Lưu ý: RTT không phải latency một chiều. Chia RTT cho 2 chỉ là một ước lượng khi đường đi và đường về tương đối đối xứng, không phải phép đo chính xác trong mọi trường hợp.
Sản phẩm hoàn thành: đo được RTT thực tế khi Client giao tiếp với Server và có thể hiển thị, ghi log hoặc xuất số liệu để làm báo cáo.
7. Throughput Measurement
Throughput cho biết lượng dữ liệu hoặc số thông điệp mà hệ thống xử lý, truyền tải được trong một khoảng thời gian. 
Đo:
Packets/second.
Bytes/second.
Message rate.
Thông lượng theo số packet:
PPS=SốpacketThờigian(s)
Thông lượng dữ liệu:
BPS=TổngbyteThờigian(s)
Chỉ số
Đơn vị
Ý nghĩa
Packets/second
packet/s
Số packet truyền hoặc nhận mỗi giây
Bytes/second
byte/s hoặc KB/s
Lượng dữ liệu truyền hoặc nhận mỗi giây
Message rate
message/s
Số message thuộc một loại hoặc toàn hệ thống mỗi giây

Ví dụ:
MOVE              → packets/s
BOMB_EXPLODE      → events/s
TOTAL TRAFFIC     → bytes/s
Lớp ThroughputMonitor có thể có các nhiệm vụ:
Tăng bộ đếm khi một packet được gửi hoặc nhận thành công.
Cộng kích thước byte tương ứng.
Ghi nhận loại packet.
Tổng hợp số liệu theo cửa sổ thời gian, chẳng hạn mỗi giây.
Trả kết quả cho phần hiển thị và ghi báo cáo.
Bạn nên phân biệt rõ byte ở tầng ứng dụng với lưu lượng thực tế trên mạng. Nếu chỉ đếm độ dài packet do Encoder tạo ra, đó là lưu lượng payload/protocol của ứng dụng, chưa bao gồm toàn bộ overhead của TCP/IP và các tầng mạng khác.
Sản phẩm hoàn thành: lớp đo throughput hoạt động và cung cấp thống kê tổng cũng như theo loại message.
8. Concurrent Client Testing
-Kiểm thử với:
1 Client
2 Clients
4 Clients
-Tạo client có thể chọn một trong các phương pháp sau:
Mở nhiều cửa sổ game nếu chương trình cho phép.
Chạy nhiều tiến trình Client độc lập trên cùng máy.
Viết một chương trình giả lập Client để tự động kết nối và gửi các message theo kịch bản.
Với công việc của bạn, viết chương trình giả lập Client là phương án hữu ích nhất nếu game chưa hoàn thiện giao diện. Chương trình này có thể dùng ClientNetwork để kết nối đến Server, gửi MOVE, PING, CHAT hoặc các message được hỗ trợ và ghi lại kết quả.
Tuy nhiên, chương trình giả lập không thay thế hoàn toàn việc kiểm thử gameplay thật. Bạn vẫn cần kiểm tra tích hợp với game thực tế.

-Đo:
Latency.
Throughput.
CPU.
Memory.
Số connection.
Tỷ lệ lỗi.
Các chỉ số cần thu thập 
Chỉ số
Ý nghĩa
Số Client kết nối
Server quản lý được bao nhiêu kết nối trong bài thử
RTT trung bình, p95
Độ trễ thông thường và độ trễ ở nhóm 5% mẫu cao nhất
Packet/s
Tần suất truyền message
Byte/s
Lưu lượng dữ liệu
CPU
Mức sử dụng bộ xử lý
Memory
Bộ nhớ tiến trình sử dụng
Error rate
Tỷ lệ thao tác hoặc message bị lỗi
Disconnect
Số kết nối bị ngắt ngoài dự kiến


9. Reliability / Error Testing
Kiểm thử:
Client mất kết nối.
Server đóng connection.
Packet không hợp lệ.
Client gửi command sai.
Nhiều Client gửi message cùng lúc.
Timeout.
Reconnect nếu nhóm triển khai.
10. Wireshark / Network Analysis
Phân tích:
TCP Handshake.
TCP packet.
Custom protocol.
MOVE message.
BOMB_EXPLODE message.
PING/PONG.
Có thể dùng Wireshark làm minh chứng trực quan trong phần demo.
File/module chính
common/protocol/
    Packet.java
    PacketType.java
    PacketEncoder.java
    PacketDecoder.java
    ProtocolException.java

common/router/
    MessageRouter.java

client/network/
    ClientNetwork.java

metrics/
    LatencyMonitor.java
    ThroughputMonitor.java
    Benchmark.java

testing/
    TestScenario.java
    NetworkTest.java
Deliverable
Protocol Specification.
Packet structure.
Encoder/Decoder.
Message Router.
Client Network framework.
Latency measurement.
Throughput measurement.
Concurrent Client test.
Reliability test.
Network performance report.


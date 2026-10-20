PHÂN CÔNG NHIỆM VỤ PROJECT BOMB IT MULTIPLAYER
1. Thành viên 1 — Network Infrastructure & Session Management (Mai)
Quản lý kết nối, người chơi và phòng
Trọng tâm Network Programming:
TCP Client/Server.
Socket Lifecycle.
Multithreading cho nhiều Client.
Connection Management.
Session Management.
Error Handling khi mất kết nối.
Công việc
1. Server Core
Xây dựng server TCP:
ServerSocket
    ↓
accept()
    ↓
ClientHandler
    ↓
ConnectionManager
Phụ trách:
Mở port và khởi động Server.
Nhận kết nối Client bằng accept().
Tạo ClientHandler cho từng Client.
Quản lý danh sách Client đang kết nối.
Đóng Socket khi Client thoát.
Xử lý IOException, SocketException, EOFException, Connection reset.
2. Session Management
Xây dựng đối tượng Session:
Session
 ├── playerId
 ├── username
 ├── socket
 ├── roomId
 └── connectionState
Quản lý trạng thái:
CONNECTED
IN_LOBBY
IN_ROOM
READY
PLAYING
DISCONNECTED
3. Room / Lobby Management
Xây dựng:
RoomManager
Room
Chức năng:
Tạo phòng.
Tham gia phòng.
Rời phòng.
Giới hạn tối đa 4 người/phòng.
Quản lý trạng thái Ready.
Kiểm tra điều kiện bắt đầu game.
Thông báo danh sách người chơi trong phòng.
Các message dự kiến:
CREATE_ROOM
JOIN_ROOM
LEAVE_ROOM
READY
START_GAME
4. Chat trong Room
Xử lý:
CHAT
Client gửi tin nhắn → Server → Broadcast cho các Client trong cùng Room.
5. Heartbeat / Keep-alive
Xây dựng cơ chế:
Server ── PING ──> Client
Server <─ PONG ─── Client
Ví dụ:
Server kiểm tra định kỳ.
Client phải phản hồi PONG.
Nếu Client không phản hồi trong khoảng thời gian quy định → đánh dấu mất kết nối.
Loại Client khỏi Room.
Broadcast PLAYER_DISCONNECTED.
6. Disconnect Handling
Xử lý các trường hợp:
Người chơi đóng game.
Mất Wi-Fi.
Server reset connection.
Socket bị timeout.
Client crash.
Kết quả phải đảm bảo:
Session được giải phóng.
Client bị xóa khỏi Room.
Game State được cập nhật.
Những người chơi còn lại nhận thông báo.
File/module chính
server/network/
    Server.java
    ClientHandler.java
    ConnectionManager.java

server/session/
    Session.java
    SessionManager.java

server/room/
    Room.java
    RoomManager.java

server/chat/
    ChatHandler.java

server/heartbeat/
    HeartbeatManager.java
Deliverable
TCP Server hoạt động.
Nhiều Client có thể cùng kết nối.
Tạo/join/leave Room.
Chat.
Heartbeat.
Disconnect detection.
Connection error handling.

2. Thành viên 2 — Real-time Movement & State Synchronization ( Chinh)
Đồng bộ di chuyển và trạng thái game
Trọng tâm Network Programming:
Multithreading.
Game Loop.
Real-time communication.
State Synchronization.
Concurrency.
Client-side Prediction.
Interpolation/Reconciliation.
Công việc
1. Game State
Xây dựng trạng thái trung tâm của trận đấu:
GameState
 ├── Map
 ├── Players
 ├── Bombs
 └── Items
Player:
Player
 ├── id
 ├── x
 ├── y
 ├── direction
 ├── speed
 └── state
Server là nơi quyết định trạng thái thật của game.
2. Xử lý MOVE
Client không tự quyết định vị trí cuối cùng.
Client gửi:
MOVE
direction = RIGHT
Server:
Nhận MOVE.
Kiểm tra Player.
Tính vị trí mới.
Kiểm tra collision.
Cập nhật GameState.
3. Command Queue
Không để nhiều ClientHandler cùng sửa GameState trực tiếp.
Mô hình:
ClientHandler A ─┐
ClientHandler B ─┼→ Command Queue
ClientHandler C ─┤
ClientHandler D ─┘
                       ↓
                   Game Loop
                       ↓
                   GameState
Có thể sử dụng BlockingQueue<GameCommand>.
4. Game Loop
Xây dựng vòng lặp:
while (running) {
    processCommands();
    updateGame();
    broadcastSnapshot();
}
Game Loop chịu trách nhiệm:
Xử lý command.
Update game state.
Collision.
Đồng bộ trạng thái.
Broadcast snapshot.
5. Collision Detection
Xử lý:
Player ↔ Wall.
Player ↔ Block.
Player ↔ Map Boundary.
Các va chạm cần thiết khác.
Server phải kiểm tra collision thay vì tin tuyệt đối vào Client.
6. Real-time State Synchronization
Server định kỳ tạo:
GAME_SNAPSHOT
Ví dụ:
Player 1 → x, y
Player 2 → x, y
Player 3 → x, y
Player 4 → x, y
Sau đó broadcast tới tất cả Client trong Room.
Ví dụ:
20 snapshots / second
7. Client-side Prediction
Khi người chơi nhấn:
RIGHT
Client có thể dự đoán di chuyển ngay thay vì chờ Server trả kết quả.
Input
  ↓
Local Prediction
  ↓
Render
  ↓
Send MOVE
  ↓
Server
8. Reconciliation
So sánh:
Client predicted position
          VS
Server authoritative position
Nếu lệch:
Điều chỉnh về trạng thái Server.
Giảm cảm giác giật khi mạng có độ trễ.
9. Interpolation
Đối với Player khác:
Nhận snapshot từ Server.
Nội suy giữa các vị trí.
Giúp chuyển động mượt hơn.
File/module chính
server/game/
    GameState.java
    GameLoop.java
    GameCommand.java

server/movement/
    MovementHandler.java
    MovementSystem.java
    CollisionSystem.java
    SnapshotBuilder.java

client/movement/
    InputController.java
    PredictionController.java
    InterpolationController.java
    ReconciliationController.java
Deliverable
Player di chuyển qua mạng.
Collision hoạt động.
Nhiều Player cùng di chuyển.
Server đồng bộ GameState.
Broadcast snapshot.
Client Prediction.
Interpolation/Reconciliation.
Không xảy ra race condition nghiêm trọng trong GameState.

3. Thành viên 3 — Bomb, Explosion, Item & Game Events ( Duy )
Xử lý gameplay event theo thời gian thực
Trọng tâm Network Programming:
Event-based networking.
Server-side game logic.
Scheduled task.
State/event synchronization.
Broadcast event.
Công việc
1. Plant Bomb
Nhận:
PLANT_BOMB
Server kiểm tra:
Player còn sống.
Vị trí hợp lệ.
Chưa vượt giới hạn số bomb.
Điều kiện gameplay phù hợp.
Nếu hợp lệ → tạo Bomb.
Bomb:
Bomb
 ├── bombId
 ├── ownerId
 ├── x
 ├── y
 ├── timer
 └── range
2. Bomb Timer
Sau khi đặt:
Bomb
 ↓
3 seconds
 ↓
Explosion
Sử dụng:
ScheduledExecutorService
thay vì tạo một Thread riêng cho từng quả bom.
3. Explosion System
Khi bomb nổ:
Xác định các ô bị ảnh hưởng.
Xử lý 4 hướng.
Kiểm tra range.
Kiểm tra wall/block.
Xác định vùng explosion.
Ví dụ:
     X
      X
X X B X X
      X
      X
4. Breakable Block
Xử lý:
Hard Wall → chặn explosion.
Breakable Block → phá block.
Xác định explosion có tiếp tục đi qua hay không theo luật game.
5. Player Damage / Death
Server kiểm tra:
Explosion
    ↓
Player nằm trong vùng nổ?
    ↓
YES
    ↓
PLAYER_HIT / PLAYER_DIE
Server là nguồn quyết định cuối cùng.
6. Item System
Quản lý item:
Item
 ├── Bomb+
 ├── Fire+
 ├── Speed+
 └── ...
Các event:
ITEM_SPAWN
ITEM_PICKUP
Server kiểm tra Player có thật sự lấy Item hay không.
7. Event Broadcasting
Các event chỉ gửi khi có sự kiện:
BOMB_EXPLODE
ITEM_SPAWN
ITEM_PICKUP
PLAYER_DIE
Không broadcast các event này liên tục như movement.
Ví dụ:
Server
  │
  │ BOMB_EXPLODE
  │
  ├── Client A
  ├── Client B
  ├── Client C
  └── Client D
8. Client Rendering
Phụ trách phần hiển thị:
Bom.
Countdown.
Explosion.
Block bị phá.
Item xuất hiện.
Player chết.
File/module chính
server/bomb/
    Bomb.java
    BombHandler.java
    BombSystem.java
    ExplosionSystem.java

server/item/
    Item.java
    ItemSystem.java

server/event/
    GameEvent.java
    EventManager.java

client/bomb/
    BombRenderer.java
    ExplosionRenderer.java

client/item/
    ItemRenderer.java
Deliverable
Đặt bomb.
Countdown.
Explosion.
Phá block.
Damage / Death.
Item spawn/pickup.
Broadcast event.
Animation tương ứng trên Client.

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
Ví dụ:
CONNECT
CREATE_ROOM
JOIN_ROOM
LEAVE_ROOM
CHAT
READY
START_GAME

MOVE

PLANT_BOMB
BOMB_EXPLODE
ITEM_SPAWN
ITEM_PICKUP
PLAYER_DIE
PLAYER_DISCONNECTED

PING
PONG

GAME_SNAPSHOT
2. Packet Structure
Thiết kế format thống nhất:
+--------+--------+----------+
| LENGTH |  TYPE  | PAYLOAD  |
+--------+--------+----------+
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
Router nhận Packet rồi chuyển cho đúng module:
Packet
   ↓
MessageRouter
   ├── MOVE → MovementHandler
   ├── PLANT_BOMB → BombHandler
   ├── CHAT → ChatHandler
   ├── PONG → HeartbeatHandler
   └── ...
Mục tiêu là tránh một if/else khổng lồ trong ClientHandler.
5. Client Network Framework
Xây dựng lớp mạng dùng chung cho Client:
ClientNetwork
 ├── connect()
 ├── send()
 ├── receive()
 └── disconnect()
TV2 và TV3 sử dụng framework này để không phải tự viết Socket networking riêng.
6. Latency Measurement
Đo RTT:
Client → PING → Server → PONG → Client
Tính:
RTT = responseTime - sendTime
Hiển thị:
Ping: 25 ms
7. Throughput Measurement
Đo:
Packets/second.
Bytes/second.
Message rate.
Ví dụ:
MOVE              → packets/s
BOMB_EXPLODE      → events/s
TOTAL TRAFFIC     → bytes/s
8. Concurrent Client Testing
Kiểm thử với:
1 Client
2 Clients
4 Clients
Có thể mở rộng cao hơn nếu server cho phép.
Đo:
Latency.
Throughput.
CPU.
Memory.
Số connection.
Tỷ lệ lỗi.
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

5. Nhiệm vụ chung của cả nhóm
Các phần này không giao độc quyền cho một thành viên vì đây là yêu cầu của toàn project.
5.1 Problem
Xác định bài toán:
Xây dựng phiên bản Bomb It Multiplayer cho phép nhiều người chơi cùng tham gia một trận đấu thông qua mạng, trong đó Server quản lý tập trung trạng thái game và đồng bộ trạng thái giữa các Client theo thời gian thực.
5.2 Objective
Hệ thống phải:
Hỗ trợ nhiều Client.
Có Room/Lobby.
Cho phép nhiều Player chơi cùng lúc.
Đồng bộ movement.
Đồng bộ bomb/item/event.
Xử lý disconnect.
Đảm bảo Server giữ GameState chính.
Đánh giá được hiệu năng mạng.
5.3 System Architecture
Thống nhất mô hình:
                   SERVER
                       │
              ┌────────┴────────┐
              │                 │
        Connection Layer    Game Layer
              │                 │
          TV1             ┌─────┴─────┐
                          │           │
                      Movement      Bomb
                         TV2          TV3
                             
                       Protocol Layer
                              TV4
                               
              ┌─────────┬─────────┬─────────┐
              │         │         │         │
           Client A  Client B  Client C  Client D
5.4 Protocol Specification
TV4 thiết kế nhưng cả nhóm phải thống nhất trước khi code.
5.5 Game Rules
Cả nhóm thống nhất:
Map.
Số Player.
Số Player tối đa mỗi Room.
Bomb timer.
Bomb range.
Speed.
Item.
Điều kiện thắng/thua.
5.6 Testing & Evaluation
Cả nhóm cùng cung cấp testcase, TV4 tổng hợp số liệu:
Test Room
Test Movement
Test Bomb
Test Synchronization
Test Disconnect
Test Concurrent Clients
Test Latency
Test Throughput
5.7 Novelty & Contributions
Nhóm nên chọn khoảng 2–4 đóng góp kỹ thuật thực sự nổi bật:
1. Custom Binary Protocol
2. Server Authoritative Game Architecture
3. Real-time State Synchronization
4. Client-side Prediction + Interpolation
5. Heartbeat / Fault Handling
6. Concurrency / Command Queue
Không nhất thiết phải làm tất cả; chỉ nên ghi những phần nhóm thực sự triển khai và đánh giá được.

6. Ranh giới code giữa 4 người
Để tránh conflict:
TV1
→ Connection / Session / Room / Chat / Heartbeat

TV2
→ Movement / Game Loop / GameState / Prediction

TV3
→ Bomb / Explosion / Item / Game Event

TV4
→ Protocol / Encoder / Decoder / Router / Metrics
Quy định:
TV1 KHÔNG xử lý MOVE
TV2 KHÔNG xử lý Bomb
TV3 KHÔNG tự thiết kế Protocol
TV4 KHÔNG tự viết Gameplay
Các module giao tiếp với nhau thông qua interface/class đã thống nhất.

7. Luồng hoàn chỉnh của hệ thống
Client
   │
   │ TCP Connection
   ▼
TV1: Connection Manager
   │
   ▼
TV4: Decoder
   │
   ▼
TV4: Message Router
   │
   ├───────────────┐
   │               │
 MOVE           PLANT_BOMB
   │               │
   ▼               ▼
TV2 Movement    TV3 Bomb
   │               │
   └───────┬───────┘
           ▼
       GameState
           │
           ▼
       Game Loop
           │
           ▼
     TV4 Encoder
           │
           ▼
       Broadcast
           │
     ┌─────┼─────┐
     ▼     ▼     ▼
   Client Client Client


Mô tả dự án: Bomb It Multiplayer
*Thể loại:
- Bomb It Multiplayer là game hành động 2D nhiều người chơi, trong đó người chơi di chuyển trên bản đồ, đặt bom phá block, thu thập vật phẩm và cạnh tranh để giành chiến thắng. Game sử dụng mô hình Client/Server, cho phép nhiều người chơi tham gia cùng một trận đấu thông qua mạng và đồng bộ trạng thái theo thời gian thực.
*Công nghệ sử dụng:
-Java: Ngôn ngữ lập trình chính để xây dựng game và xử lý logic.
-Java 2D (AWT/Swing): Hiển thị bản đồ, nhân vật, bom, hiệu ứng nổ và xây dựng giao diện điều khiển.
-Tile Map: Biểu diễn bản đồ dạng lưới, quản lý tường, block và vị trí các đối tượng trong game.
-Maven: Quản lý cấu trúc dự án, thư viện và quá trình biên dịch.
-TCP Socket: Thiết lập kết nối và trao đổi dữ liệu giữa Server và các Client trong chế độ multiplayer.
*Công nghệ lập trình mạng:
-Mô hình: Client–Server, trong đó Server quản lý trạng thái chính thức của trận đấu.
-Giao thức: TCP/IP, sử dụng kết nối TCP để trao đổi dữ liệu giữa các thành phần.
-Trao đổi dữ liệu: Sử dụng packet với các trường xác định loại thông điệp và nội dung dữ liệu; nhóm xây dựng cơ chế mã hóa, giải mã và định tuyến thông điệp.
-Server: Quản lý kết nối và phòng chơi; xử lý di chuyển, va chạm, đặt bom, vụ nổ và vật phẩm; đồng bộ trạng thái game và phát sự kiện tới các Client.
-Client: Gửi thao tác của người chơi, nhận trạng thái và sự kiện từ Server, hiển thị gameplay và cập nhật chuyển động của các nhân vật.
Đồng bộ và xử lý đồng thời: Sử dụng Game Loop, hàng đợi lệnh và cơ chế đồng bộ trạng thái để xử lý nhiều người chơi; hỗ trợ phát hiện mất kết nối và đánh giá hiệu năng mạng.


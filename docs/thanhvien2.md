Tổng quan lộ trình
Bạn nên chia công việc thành 9 bước:
Thống nhất kiến trúc với ba thành viên còn lại.
Xây dựng Player, GameMap và GameState.
Xây dựng GameCommand và Command Queue.
Xây dựng MovementSystem.
Xây dựng CollisionSystem.
Xây dựng GameLoop.
Xây dựng SnapshotBuilder và đồng bộ với Client.
Làm Client Prediction, Interpolation và Reconciliation.
Kiểm thử và tích hợp toàn bộ.
Mình sẽ phân tích lần lượt từng bước.
Bước 1. Thống nhất cách hoạt động của game trước khi code
Đây là bước dễ bị bỏ qua nhất nhưng lại quyết định việc bạn có phải sửa lại code sau này hay không.
Trước tiên, hãy trao đổi với ba thành viên còn lại về những vấn đề sau.
1.1. Thống nhất cách biểu diễn bản đồ
Vì game của nhóm là Bomb It, mình khuyên dùng bản đồ dạng ô vuông (Tile Map).
Ví dụ:
##########
#P.......#
#..##....#
#........#
#....B...#
##########
Quy ước:
#: tường hoặc block.
P: vị trí người chơi.
B: vị trí bom.
.: ô trống.
Bạn nên tách hai hệ tọa độ:
Pixel coordinates: vị trí hiển thị và di chuyển của Player trên màn hình.
Tile coordinates: vị trí ô bản đồ được dùng để kiểm tra vật cản, đặt bom và tính vùng nổ.
Ví dụ, nếu một ô bản đồ rộng 48 pixel, thì Player tại pixel (120, 100) có thể đang chiếm ô chứa tọa độ tile (2, 2), tùy cách bạn xác định gốc tọa độ.
Nên thống nhất các thông số:
tileSize = 48 pixels
mapWidth = số cột của bản đồ
mapHeight = số hàng của bản đồ
Đây chỉ là ví dụ ban đầu, nhóm có thể chọn thông số khác.
1.2. Thống nhất cách di chuyển
Với game Bomb It, mình khuyên sử dụng chuyển động liên tục trên màn hình, nhưng kiểm tra vật cản theo các ô của bản đồ.
Ví dụ:
Nhấn RIGHT
    ↓
Player di chuyển sang phải
    ↓
Server kiểm tra vật cản
    ↓
Player không được đi xuyên tường
Không nên để Client gửi liên tục tọa độ pixel rồi yêu cầu Server chấp nhận. Client nên gửi ý định di chuyển, còn Server tính vị trí thật.
1.3. Thống nhất cách Client gửi lệnh
Bạn và TV4 nên thống nhất message MOVE.
Với chuyển động liên tục, có thể quy định payload như sau:
TYPE: MOVE
direction: RIGHT
moving: true
Khi nhả phím:
TYPE: MOVE
direction: RIGHT
moving: false
Như vậy Server biết người chơi đang giữ phím hay đã dừng.
Đây là một đề xuất thiết kế; TV4 cần xác nhận cấu trúc cuối cùng trong protocol.md.
Lưu ý: playerId không nên được tin trực tiếp từ payload. Server lấy danh tính người chơi từ Session/Connection mà TV1 quản lý.
1.4. Thống nhất trách nhiệm giữa các thành viên
Bạn nên chốt ranh giới như sau:
TV1 quản lý ai đang kết nối và ai thuộc phòng nào.
Bạn (TV2) quản lý GameState, chuyển động, Game Loop và snapshot.
TV3 quản lý bom, vụ nổ, vật phẩm và logic gameplay tương ứng.
TV4 quản lý Packet, Encoder/Decoder, Message Router và giao thức.
Ví dụ khi bom nổ, TV3 xác định những ô nào bị ảnh hưởng; bạn cung cấp API để cập nhật trạng thái bản đồ/người chơi trong GameState; TV4 xử lý đóng gói dữ liệu; tầng mạng gửi kết quả cho các Client.
Kết quả cần đạt ở bước 1: có một tài liệu ngắn ghi rõ tọa độ, kích thước ô, kiểu lệnh di chuyển, các API dùng chung và người sở hữu từng file.
Bước 2. Xây dựng Player, GameMap và GameState
Đây là nền móng của toàn bộ phần việc bạn phụ trách.
Bạn chưa cần kết nối Socket ở bước này. Hãy đảm bảo Server có thể lưu và cập nhật trạng thái của một trận đấu.
2.1. Tạo Player.java
Vị trí:
src/main/java/server/game/Player.java
Class Player lưu trạng thái của một người chơi.
Những thuộc tính cơ bản nên có:
Player
 ├── id
 ├── x
 ├── y
 ├── direction
 ├── speed
 ├── moving
 └── alive
Ý nghĩa:
id: mã định danh người chơi.
x, y: tọa độ pixel do Server quản lý.
direction: hướng di chuyển hiện tại.
speed: tốc độ di chuyển.
moving: có đang yêu cầu di chuyển hay không.
alive: người chơi còn sống hay không.
Bạn có thể dùng enum Direction:
public enum Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT
}
Không nên lưu hướng dưới dạng chuỗi tùy ý như "right", "RIGHT" hay "Right" vì dễ phát sinh lỗi so sánh.
Bạn cũng nên thống nhất với TV3 về cách trạng thái chết được thể hiện. Nếu TV3 xử lý sự kiện người chơi chết, bạn vẫn cần một API để cập nhật trạng thái Player thông qua cơ chế của GameState.
2.2. Tạo GameMap.java
Vị trí:
src/main/java/server/game/GameMap.java
Class này lưu cấu trúc bản đồ phía Server.
Ví dụ, bạn có thể dùng mảng hai chiều:
int[][] tiles;
Hoặc dùng enum TileType để code rõ nghĩa hơn:
public enum TileType {
    EMPTY,
    HARD_WALL,
    BREAKABLE_BLOCK
}
Khi đó:
EMPTY             → đi qua được
HARD_WALL         → không đi qua được
BREAKABLE_BLOCK   → không đi qua được cho đến khi bị phá
Các method nên cung cấp:
getTile(x, y)
isWalkable(tileX, tileY)
destroyBlock(tileX, tileY)
Bạn không cần xử lý thuật toán nổ bom tại đây. TV3 sẽ quyết định block nào bị phá; GameMap chỉ cung cấp cách truy vấn và cập nhật bản đồ theo API đã thống nhất.
Vì bản đồ dạng tile, tọa độ pixel của Player có thể được quy đổi thành tọa độ ô:
tileX = floor(playerX / tileSize)
tileY = floor(playerY / tileSize)
Đây là cách đơn giản để xác định Player đang nằm trong khu vực bản đồ nào.
2.3. Tạo GameState.java
Vị trí:
src/main/java/server/game/GameState.java
Class này quản lý trạng thái của một trận đấu.
Ví dụ:
GameState
 ├── GameMap
 ├── Players
 ├── trạng thái trận đấu
 └── dữ liệu gameplay dùng chung
Những method cơ bản có thể là:
addPlayer(player)
removePlayer(playerId)
getPlayer(playerId)
getPlayers()
getMap()
Bạn cũng cần xem xét cách trả về danh sách Player để module khác không thể tùy ý sửa dữ liệu bên trong.
Một nguyên tắc quan trọng: GameState nên thuộc về một Room đang chơi, không phải toàn bộ các Room cùng chia sẻ một trạng thái duy nhất.
Nếu có Room 1 và Room 2, mỗi trận đấu cần GameState riêng để người chơi ở hai phòng không ảnh hưởng lẫn nhau.
Khi nào hoàn thành bước 2?
Bạn có thể viết một chương trình test đơn giản để:
Tạo GameMap.
Tạo 2–4 Player.
Thêm Player vào GameState.
Lấy Player theo ID.
Đọc và thay đổi tọa độ.
Kiểm tra loại tile ở một vị trí.
Nếu chưa làm được những việc này độc lập thì chưa nên chuyển sang mạng.
Bước 3. Xây dựng GameCommand và Command Queue
Đây là phần thể hiện kiến thức Multithreading và Concurrency rất rõ.
Vấn đề cần giải quyết: mỗi Client có thể được xử lý bởi một thread mạng riêng. Nếu tất cả các thread cùng sửa tọa độ Player trong GameState thì dữ liệu dễ bị tranh chấp.
Ví dụ:
ClientHandler A ─┐
ClientHandler B ─┼──> Cùng sửa GameState
ClientHandler C ─┘
Nếu xử lý không đúng, một Player có thể bị cập nhật sai vị trí hoặc trạng thái bị ghi đè.
3.1. Tạo GameCommand.java
Vị trí:
src/main/java/server/game/GameCommand.java
Đây là đối tượng đại diện cho một yêu cầu cần được Game Loop xử lý.
Ví dụ command di chuyển gồm:
GameCommand
 ├── playerId
 ├── direction
 ├── moving
 └── inputSequence
inputSequence có thể dùng về sau khi triển khai reconciliation; ở phiên bản đầu, bạn có thể chưa cần.
Ví dụ một Client bấm phím phải, Server tạo command tương ứng:
playerId = 12
direction = RIGHT
moving = true
Command không trực tiếp cập nhật tọa độ. Nó chỉ mang yêu cầu đến Game Loop.
3.2. Tạo Command Queue
Bạn có thể sử dụng:
BlockingQueue<GameCommand>
Ví dụ, các thread mạng chỉ đưa command vào hàng đợi:
ClientHandler A ─┐
ClientHandler B ─┼──> BlockingQueue
ClientHandler C ─┘          |
                             v
                          GameLoop
                             |
                             v
                         GameState
Bạn nên nghiên cứu các method:
put(command): đưa command vào hàng đợi, có thể chờ nếu hàng đợi bị giới hạn và đã đầy.
offer(command): thử đưa command vào hàng đợi, trả về trạng thái thành công hoặc thất bại.
poll(): lấy command tiếp theo nếu có, không chờ khi hàng đợi trống.
Đối với game, bạn nên đặt giới hạn hàng đợi hoặc có cơ chế kiểm soát input hợp lý để tránh Client gửi vô hạn message làm tiêu tốn tài nguyên.
Điểm quan trọng nhất:
ClientHandler không sửa tọa độ Player trực tiếp. Nó xác thực thông tin cần thiết rồi đưa command vào hàng đợi. Game Loop mới thực hiện cập nhật.
Khi nào hoàn thành bước 3?
Hãy viết test để:
Đưa nhiều command vào Queue.
Lấy chúng ra theo thứ tự.
Kiểm tra các command không bị mất trong một kịch bản test đơn giản.
Thử đưa command từ nhiều thread.
Xác nhận việc đưa command vào Queue không trực tiếp sửa GameState.
Bước 4. Xây dựng MovementSystem
Đến đây bạn mới bắt đầu viết logic di chuyển thực sự.
Vị trí:
src/main/java/server/movement/MovementSystem.java
4.1. Method chịu trách nhiệm di chuyển
Bạn có thể thiết kế API ban đầu như:
updateMovement(player, deltaTime, gameMap)
Trong đó:
player: người chơi cần cập nhật.
deltaTime: khoảng thời gian kể từ lần cập nhật trước.
gameMap: bản đồ dùng để kiểm tra va chạm.
Không nhất thiết phải dùng đúng chữ ký trên; quan trọng là MovementSystem không tự tạo Server hay tự đọc Socket.
4.2. Tính tọa độ mới
Giả sử:
speed = 150 pixels/second
deltaTime = 0.05 seconds
Khi Player di chuyển sang phải:
newX = oldX + speed × deltaTime
newY = oldY
Trong ví dụ:
newX = oldX + 7.5 pixels
Công thức có tác dụng giúp chuyển động không phụ thuộc trực tiếp vào số lần vòng lặp được chạy trong một giây.
Nếu bạn chỉ cộng một số pixel cố định trong mỗi vòng lặp thì tốc độ có thể thay đổi khi máy chạy nhanh hoặc chậm.
Bạn cũng cần tránh để đường chéo nhanh hơn đường thẳng nếu cho phép đi chéo. Với gameplay bốn hướng như Bomb It, ban đầu có thể chỉ cho phép đi một hướng tại một thời điểm.
4.3. Xử lý trạng thái di chuyển
Mỗi tick của Game Loop:
Đọc trạng thái moving.
Đọc direction.
Nếu Player không di chuyển hoặc đã chết thì không cập nhật vị trí.
Tính vị trí dự kiến.
Yêu cầu CollisionSystem kiểm tra.
Chỉ cập nhật vị trí hợp lệ.
Bạn cần xử lý trường hợp nhấn giữ một phím, nhả phím, chuyển hướng và nhận input mới trong lúc Player đang di chuyển.
4.4. Những gì không nên đặt trong MovementSystem
Không nên để class này:
Đọc trực tiếp dữ liệu từ Socket.
Gửi packet.
Vẽ Player lên màn hình.
Tự xử lý thời gian nổ bom.
Tự quản lý danh sách Room.
MovementSystem chỉ nên tập trung vào việc thay đổi chuyển động dựa trên trạng thái hiện tại và luật va chạm.
Khi nào hoàn thành bước 4?
Bạn cần chứng minh một Player có thể di chuyển theo bốn hướng trong một bản đồ test mà chưa cần có mạng và giao diện hoàn chỉnh.
Bước 5. Xây dựng CollisionSystem
Vị trí:
src/main/java/server/movement/CollisionSystem.java
Đây là thành phần được MovementSystem sử dụng trước khi cập nhật vị trí Player.
5.1. Kiểm tra vị trí dự kiến
Thay vì di chuyển Player trước rồi mới phát hiện đi xuyên tường, hãy tính vị trí dự kiến và kiểm tra trước.
Vị trí hiện tại
      ↓
Tính vị trí dự kiến
      ↓
CollisionSystem kiểm tra
      ↓
   Có va chạm?
    /      \
   Có       Không
   |          |
Không di    Chấp nhận
chuyển      vị trí mới
5.2. Kiểm tra vật cản
CollisionSystem cần kiểm tra:
Ranh giới bản đồ.
Tường cứng.
Block chưa bị phá.
Các vùng chiếm chỗ của Player.
Nếu Player được biểu diễn bằng hình chữ nhật thì cần kiểm tra hộp va chạm (bounding box), không chỉ kiểm tra một điểm chính giữa.
Ví dụ Player rộng 24 pixel và cao 32 pixel; khi đi qua một cửa hẹp, vị trí tâm có thể vẫn nằm trong ô trống nhưng một phần hình chữ nhật đã chạm tường.
Bạn cần chọn kích thước hitbox phù hợp với thiết kế giao diện. Ban đầu, hãy làm hitbox đơn giản rồi kiểm thử trước khi mở rộng.
5.3. Kiểm tra va chạm theo từng trục
Một cách đơn giản để chuyển động mượt là kiểm tra trục X và Y riêng:
Tính vị trí X mới, giữ nguyên Y.
Kiểm tra va chạm và chấp nhận/từ chối X.
Tính vị trí Y mới, giữ nguyên X đã được chấp nhận.
Kiểm tra va chạm và chấp nhận/từ chối Y.
Cách này giúp Player trượt dọc theo tường thay vì bị dừng hoàn toàn khi chạm góc.
Bạn nên phối hợp với TV3 để thống nhất API bản đồ khi block bị phá. CollisionSystem phải nhìn thấy bản đồ đã cập nhật, nếu không Player sẽ tiếp tục bị chặn bởi block đã biến mất.
Khi nào hoàn thành bước 5?
Hãy kiểm thử ít nhất các trường hợp:
Đi thẳng vào tường.
Đi dọc theo tường.
Đi sát góc.
Chạy tới ranh giới bản đồ.
Đi vào block.
Di chuyển sau khi block bị phá.
Bước 6. Xây dựng GameLoop
Vị trí:
src/main/java/server/game/GameLoop.java
Đây là thành phần điều phối quan trọng nhất trong module của bạn.
Nó kết nối Command Queue, MovementSystem, CollisionSystem và GameState.
6.1. Game Loop làm gì?
Một vòng lặp có thể bao gồm:
1. Nhận command
2. Cập nhật trạng thái input
3. Tính chuyển động
4. Kiểm tra va chạm
5. Cập nhật trạng thái game
6. Tạo snapshot khi đến hạn
7. Lặp lại
Phác thảo:
while (running) {
    processCommands();
    updateGame();
    broadcastSnapshot();
}
Đây mới là khung minh họa. Bạn vẫn cần xử lý thời gian, trạng thái dừng, lỗi và việc gửi snapshot không làm block Game Loop.
6.2. Thiết lập tần suất cập nhật
Một mốc thử nghiệm dễ bắt đầu là:
20 game ticks / second
Tức mỗi tick có khoảng 50 ms.
Tuy nhiên, cần phân biệt:
Game tick: tần suất Server cập nhật gameplay.
Snapshot rate: tần suất gửi trạng thái cho Client.
Render frame rate: tần suất Client vẽ giao diện.
Ba tần suất này không bắt buộc phải giống nhau.
Ở giai đoạn đầu, bạn có thể dùng 20 tick/giây và 20 snapshot/giây để đơn giản hóa. Sau khi chạy ổn, hãy thử tăng hoặc giảm snapshot rate và đo hiệu năng.
Để tránh sai số tích lũy, Game Loop nên đo thời gian thực tế giữa các lần cập nhật bằng System.nanoTime(). Đừng dựa hoàn toàn vào Thread.sleep(50) để cho rằng mỗi tick luôn kéo dài đúng 50 ms.
6.3. Ai được sửa GameState?
Theo thiết kế mình khuyên dùng:
Các thread mạng: nhận message và đưa command vào Queue.
Game Loop: xử lý command và cập nhật trạng thái chuyển động.
Module bom/vật phẩm của TV3: đưa các tác vụ thay đổi gameplay vào cơ chế cập nhật chung đã thống nhất.
SnapshotBuilder: đọc trạng thái và tạo dữ liệu snapshot.
Nguyên tắc này giúp giảm đáng kể race condition mà không cần đặt synchronized ở mọi nơi.
Khi nào hoàn thành bước 6?
Bạn cần chạy được một Server có GameLoop liên tục, xử lý nhiều command và cập nhật vị trí Player theo thời gian.
Bước 7. Xây dựng SnapshotBuilder và đồng bộ trạng thái
Đây là bước bạn nối gameplay với mạng.
Vị trí:
src/main/java/server/movement/SnapshotBuilder.java
7.1. Snapshot là gì?
Snapshot là bản chụp trạng thái game tại một thời điểm.
Ví dụ:
GAME_SNAPSHOT
  tick = 120

  Player 1:
    x = 120
    y = 96
    direction = RIGHT

  Player 2:
    x = 240
    y = 192
    direction = UP
Đây chỉ là ví dụ dữ liệu; cách đóng gói cuối cùng phụ thuộc vào TV4.
Snapshot phiên bản đầu nên ưu tiên trạng thái di chuyển của Player. Không nhất thiết phải gửi toàn bộ map, block, bomb và item trong mỗi snapshot, vì chúng có thể được đồng bộ qua các cơ chế khác.
7.2. SnapshotBuilder làm gì?
Nó đọc trạng thái cần thiết và tạo một đối tượng snapshot độc lập, thường là một bản sao dữ liệu bất biến (immutable snapshot).
Lý do cần bản sao: nếu bạn đưa trực tiếp đối tượng Player đang được Game Loop cập nhật cho thread gửi mạng, dữ liệu có thể thay đổi trong lúc đóng gói hoặc gửi.
Bạn và TV4 cần thống nhất DTO hoặc cấu trúc snapshot nào được Encoder sử dụng.
7.3. Ai gửi snapshot?
Bạn chịu trách nhiệm xây dựng snapshot và xác định thời điểm cần cập nhật.
Nhưng không nên để SnapshotBuilder tự mở Socket.
Phần gửi thực tế phải thông qua giao diện broadcast đã được nhóm thống nhất:
TV1 biết Client nào thuộc Room nào.
TV4 biết cách encode snapshot thành packet.
Module gửi mạng chuyển packet đến đúng các Client.
Một điểm rất quan trọng: đừng để Game Loop phải chờ gửi dữ liệu lần lượt đến tất cả các Socket, vì một Client chậm có thể khiến cả trận đấu bị khựng. Hãy phối hợp với TV1/TV4 để việc gửi mạng được xử lý phù hợp, chẳng hạn thông qua hàng đợi gửi riêng.
7.4. Kiểm thử synchronization
Bạn nên làm theo thứ tự:
Chạy Server với một Client.
Kiểm tra Client nhận đúng vị trí Player.
Mở Client thứ hai.
Di chuyển Player 1 và kiểm tra Player 2 nhìn thấy vị trí mới.
Thử nhiều Client cùng di chuyển.
Kiểm tra một Player disconnect thì trạng thái còn lại được cập nhật.
Khi nào hoàn thành bước 7?
Một Client gửi lệnh di chuyển, Server cập nhật GameState, snapshot được tạo và các Client trong cùng Room nhìn thấy trạng thái mới.
Đây là mốc quan trọng nhất của module bạn.
Bước 8. Làm Client InputController, Prediction và Interpolation
Chỉ bắt đầu bước này khi bước 1–7 đã chạy ổn định.
Bạn sẽ làm việc với phần Client, nhưng cần phân biệt rõ lệnh di chuyển với trạng thái thực tế từ Server.
8.1. InputController
Vị trí:
src/main/java/client/movement/InputController.java
Nhiệm vụ:
Phát hiện người chơi nhấn phím.
Gửi lệnh di chuyển khi trạng thái phím thay đổi.
Gửi trạng thái dừng khi nhả phím.
Không tự sửa tọa độ Server.
Không tạo Socket riêng nếu ClientNetwork của TV4 đã cung cấp.
8.2. Client-side Prediction
Prediction giúp nhân vật do người chơi điều khiển phản hồi nhanh hơn.
Không dùng prediction:
Nhấn RIGHT
   ↓
Gửi lệnh
   ↓
Chờ Server
   ↓
Di chuyển trên màn hình
Có prediction:
Nhấn RIGHT
   ├──> Client dự đoán di chuyển ngay
   └──> Gửi lệnh tới Server
                 ↓
          Server cập nhật
                 ↓
        Client nhận snapshot
Bạn có thể tạo:
PredictionController.java
Lưu ý: Prediction chỉ là trạng thái dự đoán phía Client, không phải trạng thái có thẩm quyền.
8.3. Reconciliation
Vị trí:
src/main/java/client/movement/ReconciliationController.java
Khi Client nhận snapshot, nó so sánh vị trí dự đoán với vị trí Server xác nhận.
Nếu lệch, Client phải điều chỉnh.
Ở phiên bản đơn giản, có thể điều chỉnh dần để giảm cảm giác giật. Nếu muốn làm reconciliation đúng hơn, cần thống nhất với TV4 trường sequence number cho lệnh input và trạng thái xác nhận từ Server. Khi đó Client có thể biết những input nào đã được Server xử lý.
Đây là phần nâng cao; đừng thêm sequence number vào protocol mà chưa được TV4 đồng ý.
8.4. Interpolation
Vị trí:
src/main/java/client/movement/InterpolationController.java
Interpolation giúp Player của người khác di chuyển mượt giữa các snapshot.
Ví dụ:
Snapshot A: x = 100
Snapshot B: x = 120
Thay vì nhảy thẳng từ 100 tới 120, Client hiển thị chuyển động giữa hai vị trí trong khoảng thời gian tương ứng.
Cách đơn giản nhất là lưu một số snapshot gần nhất rồi nội suy vị trí hiển thị. Không được lấy vị trí dự đoán của Player cục bộ làm nguồn duy nhất cho mọi Player khác.
Khi nào hoàn thành bước 8?
Player của chính mình phản hồi nhanh.
Các Player khác chuyển động tương đối mượt.
Client vẫn sửa được sai lệch khi Server gửi trạng thái chính thức.
Client không thể tự quyết định vị trí xuyên tường.
Nếu thời gian môn học không đủ, hãy ưu tiên hoàn thiện synchronization cơ bản trước Prediction và Interpolation.
Bước 9. Kiểm thử, tích hợp và chứng minh kiến thức mạng
Bạn nên xây dựng test từ sớm, không chờ đến cuối project.
Tài liệu của thầy yêu cầu project phải được kiểm thử và đánh giá bằng các tiêu chí như correctness, response time, throughput, concurrent users, reliability và network performance.
Pasted markdown.md
9.1. Kiểm thử Movement
Player di chuyển được bốn hướng.
Nhả phím thì dừng.
Player không xuyên tường.
Player không đi ra khỏi map.
Hai Player cùng di chuyển không ghi đè sai dữ liệu nhau.
9.2. Kiểm thử Game Loop và Concurrency
Nhiều thread gửi command cùng lúc.
GameState không bị sửa trực tiếp từ các thread mạng.
Command của một Client không tác động đến Player khác.
Game Loop vẫn tiếp tục chạy khi có Client gửi dữ liệu không hợp lệ.
Queue không tăng vô hạn khi Client gửi quá nhiều input.
9.3. Kiểm thử Synchronization
Một Client nhìn thấy trạng thái Player còn lại.
Nhiều Client nhận snapshot của cùng một trạng thái Server.
Client ngắt kết nối thì trạng thái được cập nhật.
Snapshot được gửi đúng Room.
Game Loop không bị chặn bởi Client mạng chậm.
9.4. Kiểm thử trong trận có bom
Bạn phải phối hợp với TV3:
Player đứng gần bom thì di chuyển đúng.
Vùng nổ tác động lên Player theo luật đã thống nhất.
Khi block bị phá, Player có thể di chuyển qua vị trí hợp lệ.
Khi Player chết, hệ thống không tiếp tục xử lý lệnh di chuyển như khi còn sống.
9.5. Đánh giá hiệu năng
Phối hợp với TV4 để đo:
RTT/Latency.
Số snapshot mỗi giây.
Lưu lượng dữ liệu.
Số Client đồng thời.
Hiện tượng giật khi nhiều người di chuyển.
Ảnh hưởng khi thay đổi snapshot rate.
Không cần đặt mục tiêu quá lớn. Bốn Client hoạt động ổn định, cùng di chuyển và nhìn thấy kết quả nhất quán đã là một cơ sở tốt để trình bày; sau đó nhóm có thể tăng số Client nếu Server và phạm vi dự án cho phép.
Cấu trúc file bạn nên làm theo
Bám theo cấu trúc cũ trong tài liệu phân công:
src/main/java/
├── server/
│   ├── game/
│   │   ├── GameState.java
│   │   ├── GameLoop.java
│   │   ├── GameCommand.java
│   │   ├── Player.java
│   │   └── GameMap.java
│   │
│   └── movement/
│       ├── MovementHandler.java
│       ├── MovementSystem.java
│       ├── CollisionSystem.java
│       └── SnapshotBuilder.java
│
└── client/
    └── movement/
        ├── InputController.java
        ├── PredictionController.java
        ├── InterpolationController.java
        └── ReconciliationController.java
Trong cấu trúc này:
Bạn sở hữu server/game/GameState.java, GameLoop.java, GameCommand.java và các file movement.
TV3 sở hữu logic bom và vật phẩm.
TV4 sở hữu protocol, Encoder/Decoder và Router.
TV1 sở hữu Session, Room và connection.
Bạn có thể điều chỉnh thêm file hoặc package khi triển khai, nhưng hãy giữ nguyên nguyên tắc sở hữu module đã thống nhất.
Bạn nên bắt đầu ngay hôm nay bằng việc gì?
Nếu chưa viết dòng code nào, đừng tạo tất cả class một lúc. Hãy thực hiện đúng ba nhiệm vụ đầu tiên:
Việc 1: Trao đổi với TV4 để chốt message di chuyển, đặc biệt trạng thái nhấn/nhả phím và cấu trúc snapshot.
Việc 2: Tạo Player.java, GameMap.java và GameState.java; kiểm tra rằng bạn có thể tạo Player và lưu trạng thái trong một trận đấu.
Việc 3: Tạo GameCommand.java và BlockingQueue<GameCommand>; thử đưa lệnh di chuyển vào hàng đợi và để một vòng lặp xử lý nó.
Sau khi hoàn thành ba việc này, bạn mới chuyển sang MovementSystem và CollisionSystem.
Mục tiêu đầu tiên không phải là có game đẹp, mà là chứng minh được chu trình sau hoạt động đúng:
Client gửi MOVE
       ↓
Server nhận command
       ↓
Command Queue
       ↓
Game Loop
       ↓
Movement + Collision
       ↓
GameState cập nhật
       ↓
Snapshot
       ↓
Client nhận vị trí mới
Khi chu trình này hoạt động ổn định, bạn đã hoàn thành phần cốt lõi nhất của nhiệm vụ số 2. Prediction và Interpolation sau đó sẽ là các bước cải thiện độ mượt, chứ không còn là trở ngại cho kiến trúc chính.


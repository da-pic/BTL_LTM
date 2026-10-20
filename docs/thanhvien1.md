0. Kiến thức Nền tảng: Kiến trúc Hệ thống Game Mạng (Dành cho người mới)

hãy hình dung kiến trúc của hệ thống này như một "Nhà hàng".
1. Hiện nay người ta dùng gì để làm mạng?
TCP: Đảm bảo gói tin đi đến nơi 100% không suy suyển. Phù hợp cho web, game đánh theo lượt, hoặc game không quá vội. (Dễ code).
UDP: Bắn gói tin đi liên tục, mất thì thôi. Phù hợp game bắn súng, đua xe cần tốc độ tức thời. (Khó code).
WebSocket: Chạy trên nền TCP, dùng cho game chạy trên trình duyệt web.
=> Project của nhóm chọn TCP, là lựa chọn cực kỳ an toàn và chuẩn mực cho Bài tập lớn.
2. Cấu trúc chia tầng (Architecture) của Game Đặt Bom nhóm mình: Hệ thống được chia thành 4 tầng rõ rệt để các bạn không code đè lên nhau:
Tầng 1: Network Layer (Tầng Mạng - Chỗ của bạn Mai): Giống như Lễ tân nhà hàng. Chuyên đón khách (Accept Socket), đuổi khách (Đóng kết nối khi rớt mạng), và cầm các gói hàng (byte[]) mà khách ném cho.
Tầng 2: Protocol Layer (Tầng Giao thức - Chỗ của Giang): Giống như Phiên dịch viên. Khách gửi một cục byte[] không ai hiểu gì. Giang sẽ lấy nó, dịch ra thành lệnh "Anh này muốn ĐI SANG PHẢI", rồi chuyển cho tầng dưới. Ngược lại, khi game có kết quả, Giang biến nó lại thành cục byte[] rồi đưa cho Mai gửi về cho khách.
Tầng 3: Session/Lobby Layer (Tầng Quản lý - Cũng là của Mai): Giống như Người xếp bàn. Nhóm 4 ông khách lại thành 1 Bàn (Room). Khách ngồi chờ (Lobby).
Tầng 4: Game Layer (Tầng Logic - Chỗ của Duy & Chinh): Giống như Nhà Bếp. Chuyên tính toán xem đi sang phải có đập đầu vào tường không (Duy), hay quả bom bao nhiêu giây thì nổ vỡ gạch (Chinh). Tầng này là trái tim của game. Toàn bộ game chạy theo mô hình Server Authoritative (Nhà bếp là Vua, khách bảo chín rồi nhưng bếp bảo sống là sống, cấm cãi - Chống hack tuyệt đối).
3. Hoạt động thực tế nó chạy thế nào?
Khách bấm phím Mũi tên phải.
Gói tin truyền qua cáp mạng.
Mai nhận được cục byte[] từ Socket.
Mai ném cục byte[] cho Giang. Giang dịch ra lệnh: MOVE_RIGHT.
Giang đưa MOVE_RIGHT cho Duy.
Duy nhìn vào bản đồ, thấy bên phải không có tường. Duy cập nhật tọa độ nhân vật nhích sang phải.
Sau đó, Duy túm toàn bộ bản đồ (GameState) ném lại cho Giang.
Giang nén bản đồ thành một cục byte[].
Giang đưa byte[] cho Mai. Mai gửi cục đó về lại cho Client.
Client nhận được, vẽ nhân vật tiến lên 1 bước. (Tất cả diễn ra trong vòng 1/30 giây).


1. Server Core (Quản lý kết nối TCP)
Phần này làm gì: Mở cổng (Port) trên máy chủ để lắng nghe người chơi kết nối. Chấp nhận kết nối (accept) và tạo ra môi trường giao tiếp riêng biệt cho từng người chơi.
Vị trí trong Project chung: Tầng Network Layer (Tầng thấp nhất). Bạn là cánh cửa duy nhất, mọi luồng data của các thành viên khác phải đi qua cửa của bạn.
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 1: Thread-per-Client (Truyền thống)
Cách làm: Dùng ServerSocket(port). Khi có Client gọi accept(), đẩy ngay kết nối đó vào ExecutorService (Thread Pool) hoặc khởi tạo Thread mới (ClientHandler) để chạy vòng lặp while(true) đọc dữ liệu.
Đánh giá Thực tế: Dùng rất nhiều trong ứng dụng nội bộ hoặc game phòng (như CSGO/Dota) vì lượng người/phòng ít. Code an toàn, dễ bắt lỗi. Giới hạn là tốn RAM nếu có hàng vạn kết nối.
Theo BTL & rule.md: Thầy yêu cầu "Xử lý nhiều client/request đồng thời". Dùng Thread Pool là đáp án tiêu chuẩn và hoàn hảo nhất cho tiêu chí Concurrency này. Mã nguồn tách biệt cũng giúp lấy điểm "Thiết kế kiến trúc hệ thống".
Chốt phương án (Đề xuất): Chọn Phương án 1. Đủ điểm tối đa phần đa luồng mà cực kỳ an toàn cho sinh viên.
Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Mô hình luồng: ServerSocket -> accept() -> ClientHandler -> ConnectionManager:
ServerSocket: Là "cổng chính" của tòa nhà Server, mở và lắng nghe cố định tại một cổng mạng (Port) cụ thể (ví dụ port 8888). Nó không trực tiếp trò chuyện với người chơi mà chỉ làm nhiệm vụ thường trực để đón kết nối mới.
accept(): Là phương thức "chặn luồng" (Blocking method) của ServerSocket. Khi Server gọi serverSocket.accept(), luồng đó sẽ đứng yên chờ đợi (không tốn CPU). Ngay khi có một Client bấm nút kết nối tới, accept() sẽ "thức dậy" và trả về một đối tượng Socket độc lập. Socket này chính là đường dây liên lạc 2 chiều riêng biệt giữa Server và người chơi đó.
ClientHandler: Là một class triển khai luồng riêng (Runnable hoặc Thread). Lý do cần làm: Nếu Server tự mình ngồi nói chuyện và đọc dữ liệu từ Socket này thì cổng chính accept() sẽ bị chặn cứng, không một người chơi nào khác có thể kết nối vào được nữa. Vì vậy, ngay khi accept() nhả ra Socket, Server lập tức giao Socket đó cho một ClientHandler mới và đẩy vào Thread Pool để phục vụ riêng người chơi đó.
ConnectionManager: Là "người quản lý sổ sách lễ tân". Lưu trữ danh sách tập trung của tất cả các ClientHandler (hoặc Session) đang online trong bộ nhớ (dùng ConcurrentHashMap) để Server có thể tra cứu và điều phối bất kỳ lúc nào.
Mở port, khởi động Server & Quản lý danh sách Client:
Mở port (new ServerSocket(port)): Chiếm giữ một cổng trên hệ điều hành để Client biết địa chỉ mà tìm đến.
Quản lý danh sách: Cực kỳ quan trọng để khi có tin nhắn chat hay có người đặt bom, Server biết đang có những ai online để gửi thông báo (broadcast).
Đóng Socket khi thoát (socket.close()): Khi một client thoát, nếu không gọi lệnh đóng socket, hệ điều hành vẫn phải giữ nguyên tài nguyên mạng ngầm (gọi là kẹt File Descriptor/Socket Port). Càng nhiều người vào ra mà không đóng thì Server sẽ bị cạn kiệt tài nguyên và sập.
Các Exception lạ (Khái niệm mạng cốt lõi - Khi nào xảy ra & Tại sao phải bắt lỗi?):
IOException (Input/Output Exception): Lỗi cha của mọi lỗi nhập/xuất dữ liệu. Xảy ra khi đường truyền mạng vật lý gặp sự cố (cáp đứt, lag nghẽn) khiến việc đọc/ghi mảng byte bị gián đoạn.
SocketException: Lỗi riêng biệt của tầng Socket mạng (con của IOException). Xảy ra khi chương trình cố gắng đọc hoặc ghi dữ liệu lên một kết nối đã bị đóng từ trước, hoặc card mạng bị tắt.
EOFException (End of File Exception): Xảy ra khi Server đang dùng lệnh đọc (ví dụ DataInputStream.readInt() chờ đọc 4 byte độ dài) nhưng Client đột ngột ngắt kết nối giữa chừng, luồng dữ liệu bị cạn kiệt trước khi đọc đủ số byte yêu cầu (-1 / End of Stream). Tình huống thực tế: Người chơi đang tải dữ liệu thì bấm Alt + F4 hoặc tắt phụt cửa sổ game.
Connection reset (Connection reset by peer): Là một lỗi cụ thể thuộc SocketException. Do hệ điều hành phía Client gửi cờ TCP RST (Reset packet) sang Server để báo hiệu kết nối bị hủy bất thường. Tình huống thực tế: Máy Client bị sập nguồn đột ngột, ứng dụng Client bị crash (văng game do lỗi code của Client), hoặc người chơi mở Task Manager bấm "End Task".
Lý do bắt buộc phải bắt (try-catch) tất cả các lỗi này: Nếu trong vòng lặp đọc dữ liệu của ClientHandler mà không bọc try-catch các lỗi trên, khi người chơi tắt game hay rớt mạng, Java sẽ ném ra ngoại lệ không được xử lý (Unhandled Exception) làm chết luồng đó và có thể gây lỗi dây chuyền làm sập toàn bộ Game Server! Thầy chấm thi sẽ cố tình tắt ngang Client để test, nếu Server crash là bị trừ điểm rất nặng.

2. Session Management (Quản lý Phiên)
Phần này làm gì: Định danh từng Socket. Lưu trữ thông tin: Socket này là của ai (PlayerID), tên gì, trạng thái nào (IN_LOBBY, IN_ROOM, PLAYING).
Vị trí trong Project chung: Tầng Session Layer. Cầu nối giữa Socket mạng và Logic Game.
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 1: Lưu trữ In-Memory bằng ConcurrentHashMap
Cách làm: Tạo class ConnectionManager chứa ConcurrentHashMap<String, Session> để lưu tất cả người đang online.
Đánh giá Thực tế: Chạy cực nhanh do lưu trực tiếp trên RAM, chuẩn mực cho game server nguyên khối (Monolithic).
Theo BTL & rule.md: Khớp tiêu chí "Quản lý trạng thái và dữ liệu". Việc dùng bản thể Concurrent (đồng thời) thay vì HashMap thường giúp tránh lỗi sập server khi nhiều client cùng Join/Leave, đảm bảo trọn điểm phần Error Handling.
Theo BTL & rule.md: Thể hiện trình độ siêu cao. Nhưng quá cồng kềnh (Overkill) cho bài tập lớn và dễ gây khó khăn khi chấm bài do yêu cầu setup môi trường lằng nhằng.
Chốt phương án: ĐÃ CHỐT PHƯƠNG ÁN 1 (ConcurrentHashMap). Đảm bảo tốc độ tối đa, thread-safe tuyệt đối, không phụ thuộc môi trường ngoài, an toàn khi nộp bài cho thầy.
Cơ chế chuyên sâu của ConcurrentHashMap (Giải thích chi tiết cho Mai):
Tại sao không dùng HashMap thường? HashMap không có cơ chế đồng bộ đa luồng. Khi 2 Client cùng lúc Join/Leave phòng, 2 luồng cùng ghi/xóa trên Map sẽ gây lỗi ConcurrentModificationException hoặc lỗi race condition làm mất dữ liệu người chơi.
Tại sao không dùng Hashtable hay Collections.synchronizedMap? Cả hai loại này đều dùng một "ổ khóa to đùng" khóa nguyên cả bảng Map. Một luồng đang đọc thì tất cả luồng khác phải đứng xếp hàng chờ đợi, làm giảm nghiêm trọng hiệu năng của game thời gian thực.
Bí mật sức mạnh của ConcurrentHashMap (Java 8+):
Thao tác đọc không khóa (Lock-free Read): Hàm get() hoàn toàn không dùng Lock. Các biến bên trong dùng từ khóa volatile giúp luồng đọc luôn thấy dữ liệu mới nhất mà không phải chờ đợi ai.
Khóa cục bộ từng ô (Node-level Locking) & CAS (Compare-And-Swap): Khi thêm một Session mới vào ô trống, nó dùng lệnh vi xử lý CAS không cần khóa. Nếu ô đã có phần tử, nó chỉ dùng synchronized khóa đúng mắt xích đầu tiên của ô đó. Hàng chục luồng khác thao tác trên các ô khác vẫn diễn ra song song hoàn toàn!
Cấu trúc Code triển khai đã chốt (Session & SessionManager):
1. server/session/Session.java:
package server.session;

import java.net.Socket;

public class Session {
    public enum State {
        CONNECTED, IN_LOBBY, IN_ROOM, READY, PLAYING, DISCONNECTED
    }

    private final String playerId;   // UUID duy nhất của người chơi
    private String username;          // Tên hiển thị người chơi nhập
    private final Socket socket;      // Kênh mạng vật lý TCP
    private String roomId;            // Mã phòng đang tham gia (null nếu ở sảnh)
    private State state;              // Trạng thái hiện tại trong game

    public Session(String playerId, Socket socket) {
        this.playerId = playerId;
        this.socket = socket;
        this.state = State.CONNECTED; // Mặc định khi vừa kết nối
        this.roomId = null;
    }

    // Getter & Setter
    public String getPlayerId() { return playerId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Socket getSocket() { return socket; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
}


2. server/session/SessionManager.java:
package server.session;

import java.io.OutputStream;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    // Lưu trữ In-Memory bằng bảng băm đa luồng: Key là playerId, Value là Session
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // Tạo phiên mới khi Client kết nối thành công
    public Session createSession(Socket socket) {
        String playerId = UUID.randomUUID().toString();
        Session session = new Session(playerId, socket);
        sessions.put(playerId, session);
        System.out.println("[SessionManager] Tạo phiên mới: " + playerId);
        return session;
    }

    // Lấy Session theo playerId (Tốc độ O(1))
    public Session getSession(String playerId) {
        return sessions.get(playerId);
    }

    // Cập nhật trạng thái người chơi
    public void updateState(String playerId, Session.State newState) {
        Session session = sessions.get(playerId);
        if (session != null) {
            session.setState(newState);
        }
    }

    // Xóa Session và giải phóng Socket khi người chơi thoát
    public void removeSession(String playerId) {
        Session session = sessions.remove(playerId);
        if (session != null) {
            try {
                if (!session.getSocket().isClosed()) {
                    session.getSocket().close();
                }
            } catch (Exception e) {
                System.err.println("[SessionManager] Lỗi đóng socket: " + e.getMessage());
            }
            System.out.println("[SessionManager] Đã dọn dẹp phiên: " + playerId);
        }
    }

    // Gửi gói tin byte cho tất cả người chơi trong cùng một phòng (Scoped Broadcast)
    public void broadcastToRoom(String roomId, byte[] packetData) {
        for (Session session : sessions.values()) {
            if (roomId.equals(session.getRoomId()) && session.getState() != Session.State.DISCONNECTED) {
                try {
                    OutputStream out = session.getSocket().getOutputStream();
                    out.write(packetData);
                    out.flush();
                } catch (Exception e) {
                    System.err.println("[SessionManager] Lỗi gửi data tới " + session.getPlayerId());
                }
            }
        }
    }

    public int getOnlineCount() {
        return sessions.size();
    }

    public ConcurrentHashMap<String, Session> getAllSessions() {
        return sessions;
    }
}


Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Bản chất của đối tượng Session là gì? Tại sao không dùng Socket thô?
Một Socket thô của Java chỉ là ống dẫn dữ liệu (chỉ chứa địa chỉ IP và số Port). Tầng Game không thể biết "cái ống nước IP 192.168.1.5 này là ai, tên gì, đang ở phòng nào, đang sống hay đã chết".
Do đó, Session chính là "Hồ sơ định danh / Căn cước công dân" của người chơi trên Server. Một Session bọc quanh các thông tin:
playerId: Mã định danh độc nhất (ví dụ UUID dạng chuỗi hoặc số int tăng dần: 1, 2, 3...) để phân biệt người này với người khác, không bao giờ trùng.
username: Tên hiển thị người chơi tự đặt khi vào game (ví dụ: "Mai_Pro", "Player_1").
socket: Tham chiếu đến chính chiếc Socket mạng để khi cần gửi dữ liệu riêng cho người này thì lấy ra dùng.
roomId: Mã ID của phòng mà người chơi đang ngồi (nếu đang ở sảnh Lobby thì giá trị này là null).
connectionState: Trạng thái hiện tại của phiên người chơi.
Ý nghĩa của 6 trạng thái (connectionState) & Vòng đời chuyển đổi (State Lifecycle):
CONNECTED: Người chơi vừa mới kết nối TCP thành công đến cổng Server, nhưng chưa gửi gói tin đăng nhập/chọn tên. Lúc này kết nối chỉ là kết nối thô.
IN_LOBBY: Người chơi đã nhập tên thành công, đang đứng ở sảnh chờ (Lobby). Tại đây họ nhìn thấy danh sách các phòng và các nút "Tạo phòng" / "Vào phòng" (roomId = null).
IN_ROOM: Người chơi đã vào một phòng cụ thể (roomId = "ROOM_1"). Họ đang ở phòng chờ nhìn thấy các người chơi khác cùng phòng và có thể chat với nhau.
READY: Người chơi bấm nút "Sẵn sàng". Biểu tượng trạng thái của họ trên màn hình đổi sang tích xanh, sẵn sàng chiến đấu và chờ chủ phòng bấm Start.
PLAYING: Trận đấu đã chính thức bắt đầu! Trạng thái này cho phép nhận các packet điều khiển nhân vật như di chuyển (MOVE), đặt bom (PLANT_BOMB).
DISCONNECTED: Người chơi bị mất kết nối mạng. Phiên này tạm thời bị ngắt để xử lý dọn dẹp hoặc kích hoạt đồng hồ đếm ngược chờ Reconnect.
Tại sao cần cơ chế Quản lý trạng thái (State Validation)?
Chống lỗi logic và gian lận (Anti-cheat căn bản): Nếu một người chơi đang ở trạng thái IN_LOBBY hoặc IN_ROOM mà Client lại gửi lên gói tin PLANT_BOMB, Server kiểm tra thấy trạng thái không phải PLAYING thì sẽ lập tức vứt bỏ gói tin đó ngay. Không có State Machine, Server sẽ bị loạn logic, người chơi có thể hack gửi lệnh đặt bom ngay từ phòng chờ!

3. Room / Lobby Management (Quản lý Phòng)
Phần này làm gì: Nhóm 4 người chơi lại thành cụm. Xử lý logic Join, Leave, Ready và kích hoạt Start Game.
Vị trí trong Project chung: Tầng Lobby Logic. Bước đệm cuối trước khi đẩy luồng cho Duy (TV2) chạy GameState.
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 2: Quản lý Phòng động (Dynamic Matchmaking)
Cách làm: Khách nằm ở Lobby. Bấm "Tạo phòng" thì Server sinh một object Room mới với UUID riêng. Khi phòng không còn ai, hủy object đó để giải phóng RAM.
Đánh giá Thực tế: Chuẩn mực của game eSport/Matchmaking hiện đại. Tối ưu bộ nhớ.
Theo BTL & rule.md: Minh chứng cực mạnh cho tiêu chí Thiết kế kiến trúc hệ thống. Thể hiện rõ tư duy "Quản lý trạng thái linh hoạt".
Chốt phương án: ĐÃ CHỐT PHƯƠNG ÁN 2 (Quản lý Phòng động - Dynamic Rooms). Tối ưu bộ nhớ RAM, tự động hủy phòng khi trống, chuẩn kiến trúc eSport hiện đại.
Cấu trúc Code triển khai đã chốt (Room & RoomManager):
1. server/room/Room.java:
package server.room;

import server.session.Session;
import java.util.ArrayList;
import java.util.List;

public class Room {
    public enum State { WAITING, PLAYING }

    private final String roomId;
    private final String roomName;
    private String hostPlayerId;
    private State state;
    private final List<Session> players = new ArrayList<>();

    public Room(String roomId, String roomName, Session host) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.hostPlayerId = host.getPlayerId();
        this.state = State.WAITING;
        this.players.add(host);
    }

    // Thêm người chơi an toàn đa luồng (Tối đa 4 người/phòng)
    public synchronized boolean addPlayer(Session session) {
        if (players.size() >= 4 || state != State.WAITING) return false;
        players.add(session);
        return true;
    }

    // Xóa người chơi & tự động chuyển Host nếu Host rời phòng
    public synchronized void removePlayer(Session session) {
        players.remove(session);
        if (session.getPlayerId().equals(hostPlayerId) && !players.isEmpty()) {
            hostPlayerId = players.get(0).getPlayerId(); // Chuyển Host cho người kế tiếp
        }
    }

    // Kiểm tra điều kiện bắt đầu trận đấu
    public boolean canStartGame() {
        if (players.size() < 2) return false; // Cần tối thiểu 2 người
        for (Session s : players) {
            // Những người không phải Host bắt buộc phải READY
            if (!s.getPlayerId().equals(hostPlayerId) && s.getState() != Session.State.READY) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() { return players.isEmpty(); }
    public List<Session> getPlayers() { return players; }
    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getHostPlayerId() { return hostPlayerId; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
}


2. server/room/RoomManager.java:
package server.room;

import server.session.Session;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {
    // Quản lý các phòng đang hoạt động: Key là roomId, Value là Room
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    // Tạo phòng mới và đưa host vào phòng
    public Room createRoom(String roomName, Session host) {
        String roomId = "ROOM_" + UUID.randomUUID().toString().substring(0, 5);
        Room room = new Room(roomId, roomName, host);
        rooms.put(roomId, room);
        host.setRoomId(roomId);
        host.setState(Session.State.IN_ROOM);
        System.out.println("[RoomManager] Đã tạo phòng: " + roomId + " bởi Host: " + host.getUsername());
        return room;
    }

    // Người chơi tham gia vào phòng
    public boolean joinRoom(String roomId, Session player) {
        Room room = rooms.get(roomId);
        if (room != null && room.addPlayer(player)) {
            player.setRoomId(roomId);
            player.setState(Session.State.IN_ROOM);
            System.out.println("[RoomManager] Player " + player.getUsername() + " đã vào phòng " + roomId);
            return true;
        }
        return false;
    }

    // Rời phòng và tự động dọn dẹp RAM nếu phòng trống
    public void leaveRoom(Session player) {
        String roomId = player.getRoomId();
        if (roomId == null) return;

        Room room = rooms.get(roomId);
        if (room != null) {
            room.removePlayer(player);
            player.setRoomId(null);
            player.setState(Session.State.IN_LOBBY);

            // Nếu không còn ai trong phòng -> giải phóng ngay khỏi RAM
            if (room.isEmpty()) {
                rooms.remove(roomId);
                System.out.println("[RoomManager] Đã tự động hủy phòng trống: " + roomId);
            }
        }
    }

    public Room getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public ConcurrentHashMap<String, Room> getAllRooms() {
        return rooms;
    }
}


Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Đối tượng Room và RoomManager:
Room: Một đối tượng đại diện cho một "Bàn chơi". Nó lưu trữ: roomId (mã phòng), roomName (tên phòng), hostPlayerId (ai là chủ phòng), danh sách người trong phòng List<Session> players, và trạng thái phòng roomState (WAITING hay PLAYING).
RoomManager: Là class đóng vai trò "Trưởng sảnh". Quản lý danh sách toàn bộ các phòng đang mở trên Server (Map<String, Room>).
Quy trình xử lý các Message dự kiến khi Client gửi lên:
CREATE_ROOM: Khi người chơi ở Lobby bấm nút "Tạo phòng" trên giao diện. Server tạo một đối tượng Room mới, gán người gửi làm Chủ phòng (Host), chuyển trạng thái của họ từ IN_LOBBY thành IN_ROOM, cấp roomId và phản hồi lại cho Client.
JOIN_ROOM: Người chơi chọn một phòng và bấm "Tham gia". Client gửi roomId. Server kiểm tra 3 điều kiện: (1) Phòng có tồn tại không? (2) Phòng đã đủ 4 người chưa? (3) Trận đấu đã bắt đầu chưa? Nếu hợp lệ, nhét Session của họ vào Room, đổi trạng thái sang IN_ROOM. Nếu không hợp lệ, trả về mã lỗi tương ứng (ví dụ: ROOM_FULL hoặc ROOM_PLAYING).
LEAVE_ROOM: Người chơi bấm nút "Rời phòng". Server rút Session ra khỏi danh sách players của phòng, đổi trạng thái họ về IN_LOBBY.
Xử lý Chủ phòng rời đi: Nếu người rời đi là Host, Server phải tự động chuyển quyền Host cho người kế tiếp trong phòng. Nếu phòng không còn ai thì hủy phòng để giải phóng bộ nhớ.
READY: Người chơi trong phòng bấm nút "Sẵn sàng". Server đổi trạng thái người đó thành READY và gửi thông báo cập nhật cho tất cả mọi người trong phòng để UI hiện dấu tích xanh. Nếu bấm lần nữa thì hủy Ready (quay về IN_ROOM).
START_GAME: Chỉ Chủ phòng (Host) mới có quyền bấm nút Bắt đầu. Khi nhận lệnh, Server kiểm tra điều kiện: Số người chơi phải đủ (ví dụ: tối thiểu 2 người, tối đa 4 người) VÀ tất cả những người khác đều đã ở trạng thái READY. Nếu thỏa mãn:
Server đổi trạng thái phòng sang PLAYING.
Server thông báo cho Tầng Game của Duy (TV2) khởi tạo bản đồ và chạy GameLoop.
Server gửi bản tin START_GAME kèm vị trí xuất phát cho cả 4 Client để chuyển màn hình sang bàn cờ game.
Quy tắc giới hạn 4 người & Thông báo danh sách:
Map game Bomb IT kinh điển được thiết kế đối xứng 4 góc cho 4 người chơi. Do đó bắt buộc giới hạn tối đa 4 người/phòng.
Mỗi khi có biến động trong phòng (có người vào, người ra, người bấm Ready/Unready), Server lập tức tạo gói tin ROOM_UPDATE chứa danh sách tên và trạng thái của các thành viên gửi về cho tất cả máy trong phòng để giao diện hiển thị đồng bộ tức thì.

4. Chat trong Room
Phần này làm gì: Gửi tin nhắn giữa những người chung phòng.
Vị trí trong Project chung: Tính năng độc lập, giao tiếp ngang hàng (người gửi -> server -> những người khác).
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 2: Tách luồng Message Queue (Pub/Sub)
Cách làm: Gói CHAT được vứt vào một BlockingQueue. Có một Thread (Worker) khác chuyên bốc tin nhắn từ Queue ra để gửi.
Đánh giá Thực tế: Không làm ảnh hưởng (block) đến luồng đọc dữ liệu chính của A. Rất tốt cho hệ thống chat lớn.
Theo BTL & rule.md: Áp dụng "Worker Queue" theo đúng gợi ý của thầy ở phần Concurrency.
Chốt phương án: ĐÃ CHỐT PHƯƠNG ÁN 2 (Tách luồng Message Queue - Worker Thread). Áp dụng mô hình Producer-Consumer chuyên nghiệp, tránh block luồng mạng chính, ăn trọn điểm Concurrency.
Cấu trúc Code triển khai đã chốt (ChatHandler):
server/chat/ChatHandler.java:
package server.chat;

import server.room.Room;
import server.room.RoomManager;
import server.session.Session;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ChatHandler implements Runnable {
    // Gói tin nhắn chat nội bộ
    public static class ChatMessage {
        public final String roomId;
        public final String senderName;
        public final String message;

        public ChatMessage(String roomId, String senderName, String message) {
            this.roomId = roomId;
            this.senderName = senderName;
            this.message = message;
        }
    }

    // Hàng đợi tin nhắn an toàn đa luồng (BlockingQueue)
    private final BlockingQueue<ChatMessage> chatQueue = new LinkedBlockingQueue<>();
    private final RoomManager roomManager;
    private boolean running = true;

    public ChatHandler(RoomManager roomManager) {
        this.roomManager = roomManager;
        // Khởi động luồng Worker chạy ngầm để tiêu thụ hàng đợi tin nhắn
        new Thread(this, "Chat-Worker-Thread").start();
    }

    // Luồng ClientHandler chỉ cần gọi hàm này (Đưa tin vào Queue và quay về ngay, mất 0.001ms)
    public void enqueueMessage(String roomId, String senderName, String message) {
        chatQueue.offer(new ChatMessage(roomId, senderName, message));
    }

    @Override
    public void run() {
        while (running) {
            try {
                // take() sẽ đứng chờ không tốn CPU; khi có tin nhắn mới rơi vào queue thì thức dậy xử lý
                ChatMessage msg = chatQueue.take();
                Room room = roomManager.getRoom(msg.roomId);
                if (room != null) {
                    String formattedMsg = "[" + msg.senderName + "]: " + msg.message + "\n";
                    byte[] data = formattedMsg.getBytes("UTF-8");

                    // Broadcast có phạm vi: chỉ gửi cho những người đang ngồi trong phòng này
                    for (Session session : room.getPlayers()) {
                        if (session.getState() != Session.State.DISCONNECTED) {
                            try {
                                OutputStream out = session.getSocket().getOutputStream();
                                out.write(data);
                                out.flush();
                            } catch (Exception e) {
                                System.err.println("[Chat] Lỗi gửi chat tới player: " + session.getPlayerId());
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Chat] Lỗi xử lý tin nhắn: " + e.getMessage());
            }
        }
    }

    public void stop() {
        this.running = false;
    }
}


Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Cơ chế CHAT hoạt động từng bước ra sao?
Bước 1: Client A nhập dòng chữ "Chào cả nhà" trên ô chat và gửi gói tin CHAT lên Server.
Bước 2: Luồng đọc mạng của Mai nhận được gói tin, tra cứu ra Session của Client A.
Bước 3: Lấy roomId = session.getRoomId(). Dùng RoomManager để tìm đối tượng Room tương ứng.
Bước 4: Lấy danh sách toàn bộ người chơi đang ngồi trong phòng đó (room.getPlayers()).
Bước 5: Lặp qua từng Session trong phòng và gửi tin nhắn đi qua socket.getOutputStream() của họ.
Khái niệm "Broadcast có phạm vi" (Scoped Broadcast / Multicast giả lập):
Broadcast thông thường: Bắn tin nhắn cho tất cả mọi người đang nối vào Server (như loa phát thanh của cả làng).
Broadcast có phạm vi trong Room: Chỉ gửi cho đúng những người cùng ngồi chung trong 1 phòng. Người ở sảnh Lobby hay người ở phòng khác tuyệt đối không nhận được. Điều này vừa bảo đảm tính riêng tư của phòng chơi, vừa tiết kiệm tối đa băng thông mạng cho hệ thống.
Tình huống thực tế cần lưu ý:
Cần kiểm tra độ dài tin nhắn (ví dụ giới hạn tối đa 100 ký tự) để chống việc người chơi cố tình spam đoạn văn bản quá dài làm nghẽn đường truyền mạng.
Tin nhắn gửi đi nên được Server gắn kèm tên người gửi: [Mai]: Chào cả nhà để các Client khác hiển thị đúng người phát ngôn.

5. Heartbeat / Keep-alive
Phần này làm gì: "Bắt mạch" để xem người chơi còn sống hay đã đứt cáp/mất wifi (do TCP không tự phát hiện ngay được).
Vị trí trong Project chung: Hệ thống Monitoring chạy ngầm.
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 2: Client chủ động (Client Ping)
Cách làm: Client tự thiết lập Timer 3s/lần gửi PING lên. Server chỉ lưu thời điểm lastPingTime. Nếu vòng lặp dọn rác của Server thấy thằng nào quá 10s không gửi PING -> Kick.
Đánh giá Thực tế: Rất tối ưu cho CPU Server.
Theo BTL & rule.md: Vẫn trọn vẹn điểm Error Handling nhưng thiết kế khôn ngoan hơn, giảm tải rõ rệt.
Chốt phương án: ĐÃ CHỐT PHƯƠNG ÁN 2 (Client chủ động Ping & Server Sweep Timeout). Tối ưu CPU Server, định kỳ quét dọn bằng ScheduledExecutorService, phát hiện rớt mạng chuẩn xác 100%.
Cấu trúc Code triển khai đã chốt (HeartbeatManager):
1. Bổ sung vào server/session/Session.java (Thuộc tính đếm nhịp tim):
// Thêm trường lưu mốc thời gian nhận tín hiệu cuối cùng
private volatile long lastHeartbeatTime = System.currentTimeMillis();

public void updateHeartbeat() {
    this.lastHeartbeatTime = System.currentTimeMillis();
}

public long getLastHeartbeatTime() {
    return lastHeartbeatTime;
}


2. server/heartbeat/HeartbeatManager.java (Tiến trình quét ngầm):
package server.heartbeat;

import server.session.Session;
import server.session.SessionManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatManager {
    private static final long TIMEOUT_THRESHOLD_MS = 10_000; // 10 giây không có tín hiệu -> coi như rớt mạng
    private static final long CHECK_INTERVAL_SECONDS = 2;    // Cứ 2 giây quét kiểm tra 1 lần

    private final SessionManager sessionManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HeartbeatManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        startMonitoring();
    }

    // Bắt đầu vòng lặp quét định kỳ
    private void startMonitoring() {
        scheduler.scheduleAtFixedRate(this::sweepInactiveSessions, 
                CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("[HeartbeatManager] Đã khởi chạy giám sát nhịp tim mạng!");
    }

    // Khi nhận được gói PING từ Client
    public void onReceivePing(Session session) {
        // 1. Cập nhật mốc thời gian sống sót
        session.updateHeartbeat();
        
        // 2. Phản hồi gói PONG lại ngay cho Client (để Client đo Ping RTT)
        try {
            byte[] pongData = "PONG\n".getBytes();
            session.getSocket().getOutputStream().write(pongData);
            session.getSocket().getOutputStream().flush();
        } catch (Exception e) {
            System.err.println("[Heartbeat] Lỗi gửi PONG: " + e.getMessage());
        }
    }

    // Vòng quét kiểm tra timeout toàn bộ Server
    private void sweepInactiveSessions() {
        long now = System.currentTimeMillis();
        for (Session session : sessionManager.getAllSessions().values()) {
            // Bỏ qua nếu đã ngắt kết nối rồi
            if (session.getState() == Session.State.DISCONNECTED) continue;

            // Nếu quá 10 giây không có tín hiệu nhịp tim
            if (now - session.getLastHeartbeatTime() > TIMEOUT_THRESHOLD_MS) {
                System.out.println("[Heartbeat] PHÁT HIỆN TIMEOUT MẠNG! Player: " + session.getPlayerId());
                // Đánh dấu mất kết nối và kích hoạt xử lý dọn dẹp ở Mục 6
                session.setState(Session.State.DISCONNECTED);
                sessionManager.removeSession(session.getPlayerId());
            }
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}


Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Heartbeat (Keep-alive) là gì? Tại sao gọi là "Bắt mạch mạng"?
Giống như bác sĩ bắt mạch tim của bệnh nhân: tim còn đập thì người còn sống, tim ngừng đập là đã qua đời. Trên mạng máy tính, Heartbeat là cơ chế hai bên liên tục gửi các gói tin tín hiệu nhỏ (tí hon) định kỳ cho nhau để xác nhận đầu bên kia vẫn đang trực tuyến bình thường.
Tại sao giao thức TCP không tự phát hiện được mất mạng? (Khái niệm cực kỳ lạ nhưng là cốt lõi của Lập trình mạng):
Hiểu lầm phổ biến: Nhiều bạn nghĩ TCP là kết nối tin cậy nên khi rớt mạng thì Server sẽ lập tức biết ngay.
Sự thật trong thực tế: TCP chỉ biết khi một bên chủ động ngắt (gửi gói tin FIN hoặc RST). Nhưng nếu người chơi BỊ RÚT DÂY MẠNG, MẤT SÓNG WI-FI, HOẶC MẤT ĐIỆN ĐỘT NGỘT, máy Client không kịp gửi bất kỳ tín hiệu nào lên Server cả!
Lúc này, Server vẫn ngây thơ nghĩ rằng kết nối vẫn sống và tiếp tục treo luồng đọc dữ liệu. Hậu quả là nhân vật của người đó sẽ đứng đơ mãi mãi trong game như một "Ghost Player" (Người chơi ma), khiến phòng chơi bị kẹt vĩnh viễn không thể kết thúc!
Cơ chế Ping - Pong hoạt động như thế nào?
Gửi định kỳ: Server (hoặc Client) dùng một Timer đếm thời gian (ví dụ 3 giây một lần) gửi gói tin PING.
Phản hồi ngay lập tức: Phía nhận được PING phải lập tức trả lời lại bằng gói tin PONG.
Cột mốc Timeout: Mỗi khi nhận được PONG, Server cập nhật lại lastPongTime = System.currentTimeMillis().
Xử lý ngắt kết nối: Một tiến trình nền của Server liên tục quét danh sách người chơi. Nếu thấy currentTime - lastPongTime > 10.000ms (quá 10 giây không thấy trả lời) -> Server khẳng định Client này đã "chết", chủ động đóng socket, loại Client khỏi Room và gửi thông báo PLAYER_DISCONNECTED cho những người chơi còn lại trong phòng.

6. Disconnect Handling (Xử lý dọn dẹp)
Phần này làm gì: Dọn dẹp tàn dư (RAM, GameState) khi có người rớt mạng, tránh lỗi Ghost Player (người chơi ma kẹt trong phòng).
Vị trí trong Project chung: Bao trùm các tầng. Phải gọi API xóa từ Network -> Session -> Room -> GameState (TV2).
Các phương án thực hiện & Đánh giá chi tiết:
Phương án 2: Soft Drop (Chờ Reconnect - Vũ khí bí mật)
Cách làm: Khi lỗi mạng -> KHÔNG xóa Session, chỉ đổi trạng thái thành DISCONNECTED và đặt đồng hồ đếm ngược 15s. Nếu Client vào lại gửi gói RECONNECT, Server update lại Socket mới vào Session cũ và chơi tiếp. Quá 15s mới Hard Drop.
Đánh giá Thực tế: Tính năng cực kỳ "đắt giá" mang lại trải nghiệm tuyệt vời cho game mobile/wifi chập chờn.
Theo BTL & rule.md: ĐÂY LÀ NOVELTY TUYỆT HẢO. Thầy chấm cực kỳ gắt phần Novelty (Điểm mới). Đề xuất này minh chứng rõ ràng cho "Cơ chế Fault tolerance / Nâng cao reliability" (như gợi ý trong rule). Nó sẽ gánh điểm cho toàn bộ nhóm!
Chốt phương án: ĐÃ CHỐT PHƯƠNG ÁN 2 (Soft Drop & Seamless Session Recovery - Điểm nhấn Novelty của Nhóm). Cơ chế chịu lỗi (Fault Tolerance) giúp cứu game khi người chơi bị rớt mạng/lag Wi-Fi, tự động khôi phục ván đấu liền mạch trong 15 giây mà không làm gián đoạn trận đấu.
Mối quan hệ tương hỗ giữa P5 (Heartbeat) và P6 (Disconnect Handling):
P5 là "Ra-đa phát hiện" (Detection): Nhiệm vụ duy nhất của P5 là bắt mạch nhịp tim. Khi người chơi bị rút cáp mạng hoặc mất Wi-Fi đột ngột, TCP câm nín hoàn toàn. P5 đếm giờ thấy quá 10 giây không có tín hiệu PING/PONG thì "thổi còi" báo động cho P6.
P6 là "Đội cấp cứu & hành động" (Action & Recovery): P6 tiếp nhận tín hiệu từ 3 nguồn:
Client chủ động bấm Thoát (gói tin DISCONNECT).
Tầng mạng P1 bắt được Exception vỡ ống dẫn (SocketException, Connection reset).
Tiếng còi báo động Timeout từ P5. Khi có tín hiệu, P6 không dại dột xóa sổ người chơi ngay (tránh ức chế cho game thủ), mà kích hoạt quy trình phục hồi phiên dưới đây.
Quy trình 5 bước chi tiết: Cơ chế Phục hồi phiên liền mạch (Seamless Session Recovery):
Bước 1: Khi rớt mạng (Kích hoạt Soft Drop):
Server đóng chiếc Socket cũ đã hỏng để giải phóng cổng mạng vật lý.
Đổi trạng thái người chơi thành DISCONNECTED (hoặc ORPHAN_SESSION).
TUYỆT ĐỐI KHÔNG XÓA Session khỏi RAM! Mọi thông tin (Tên, ID, Phòng, Vị trí) vẫn được bảo toàn nguyên vẹn trong SessionManager.
Server khởi động một đồng hồ đếm ngược đúng 15 giây bằng ScheduledExecutorService.
Bước 2: Phối hợp Tầng Game (Đóng băng nhân vật):
Server báo cho Tầng Game của Duy (TV2) biết người chơi này đang bị mất mạng tạm thời.
Duy tạm thời "đóng băng" nhân vật trong GameLoop: Nhân vật đứng yên tại vị trí cũ, không nhận các lệnh di chuyển rác, nhưng chưa bị xóa khỏi map.
Bước 3: Client có mạng lại và gửi yêu cầu Reconnect:
Khi máy Client kết nối lại được Wi-Fi, ứng dụng Client tạo một đường truyền mới (new Socket) kết nối đến Server.
Client lập tức gửi gói tin tái kết nối: RECONNECT(playerId = "p123") (Client vẫn ghi nhớ mã ID của mình).
Bước 4: "Thay ruột Socket" & Đồng bộ toàn diện (Khoảnh khắc ăn trọn điểm Novelty):
Server tra cứu trong SessionManager thấy Session của "p123" vẫn còn trên RAM và chưa hết thời hạn 15 giây.
Server lập tức thực hiện ghép nối:
session.setSocket(newSocket); // Cắm dây mạng mới vào hồ sơ người chơi cũ!


Hủy chiếc đồng hồ đếm ngược 15 giây.
Đổi trạng thái Session từ DISCONNECTED trở lại thành PLAYING.
Server yêu cầu Duy (TV2) xuất toàn bộ trạng thái map hiện tại (tọa độ các người chơi khác, gạch vỡ, bom đang nổ) thành gói tin FULL_STATE_SYNC và bắn qua newSocket cho Client.
Màn hình Client cập nhật lại bản đồ tức thì và người chơi tiếp tục bấm phím chơi tiếp bình thường như chưa từng có gián đoạn!
Bước 5: Kịch bản quá hạn 15 giây (Thực hiện Hard Drop dọn rác):
Nếu sau 15 giây đếm ngược mà Client vẫn không thể quay lại mạng:
Server kết luận người chơi đã bỏ cuộc thật sự. Đồng hồ nổ chuông và kích hoạt quy trình Hard Drop: Xóa vĩnh viễn Session khỏi SessionManager, rút khỏi Room, báo Duy xóa avatar nhân vật, và broadcast gói tin PLAYER_DISCONNECTED cho những người cùng phòng.
Bảng phân công phối hợp 4 thành viên (Dành cho cả nhóm đọc để khớp việc):
🧑‍💻 Mai (TV1 - Bạn): Quản lý vòng đời Socket, lưu giữ Session trên RAM, điều khiển Timer đếm ngược 15 giây, và thực hiện hàm ghép newSocket vào Session cũ.
🧑‍💻 Duy (TV2): Khi nhận tín hiệu từ Mai thì đóng băng nhân vật trong GameLoop, cung cấp hàm getFullStateSyncPacket() để xuất dữ liệu bản đồ cho Mai gửi đi.
🧑‍💻 Chinh (TV3): Đảm bảo hệ thống nổ bom không bị lỗi NullPointerException nếu bom nổ trúng người chơi đang trong trạng thái đóng băng.
🧑‍💻 Giang (TV4): Bổ sung 2 loại bản tin vào bảng Protocol chuẩn: RECONNECT (Client -> Server) và FULL_STATE_SYNC (Server -> Client).
Cấu trúc Code triển khai đã chốt (Session Recovery & Cleanup):
1. Bổ sung hàm thay Socket trong server/session/Session.java:
// Thay thế socket mới khi người chơi reconnect thành công
public void attachNewSocket(Socket newSocket) {
    this.socket = newSocket;
    this.state = State.PLAYING;
    this.updateHeartbeat();
}


2. Bổ sung hàm xử lý Soft Drop & Reconnect trong server/session/SessionManager.java:
package server.session;

import java.net.Socket;
import java.util.concurrent.*;

public class SessionManager {
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    // Quản lý các đồng hồ đếm ngược 15 giây cho từng người chơi bị ngắt kết nối
    private final ConcurrentHashMap<String, ScheduledFuture<?>> disconnectTimers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timerScheduler = Executors.newScheduledThreadPool(2);

    // KHI MẤT KẾT NỐI: Kích hoạt Soft Drop (Chờ 15s)
    public void handleSoftDisconnect(String playerId) {
        Session session = sessions.get(playerId);
        if (session == null || session.getState() == Session.State.DISCONNECTED) return;

        System.out.println("[Recovery] Người chơi " + playerId + " mất kết nối. Đóng băng & chờ 15s!");
        session.setState(Session.State.DISCONNECTED);

        // Bắt đầu đếm ngược 15 giây
        ScheduledFuture<?> timer = timerScheduler.schedule(() -> {
            System.out.println("[Recovery] Hết 15s chờ đợi! Xóa sổ vĩnh viễn player: " + playerId);
            executeHardDrop(playerId);
        }, 15, TimeUnit.SECONDS);

        disconnectTimers.put(playerId, timer);
    }

    // KHI CLIENT KẾT NỐI LẠI THÀNH CÔNG: Ghép nối lại Session
    public boolean handleReconnect(String playerId, Socket newSocket) {
        Session session = sessions.get(playerId);
        // Kiểm tra session còn tồn tại và đang ở trạng thái chờ không
        if (session != null && session.getState() == Session.State.DISCONNECTED) {
            // 1. Hủy ngay đồng hồ đếm ngược 15s
            ScheduledFuture<?> timer = disconnectTimers.remove(playerId);
            if (timer != null) timer.cancel(true);

            // 2. Thay Socket mới vào Session cũ
            session.attachNewSocket(newSocket);
            System.out.println("[Recovery] THÀNH CÔNG! Đã phục hồi phiên chơi cho: " + playerId);
            return true;
        }
        return false;
    }

    // KHI QUÁ 15s HOẶC THOÁT HẲN: Xóa sổ triệt để (Hard Drop)
    public void executeHardDrop(String playerId) {
        disconnectTimers.remove(playerId);
        Session session = sessions.remove(playerId);
        if (session != null) {
            try {
                if (!session.getSocket().isClosed()) session.getSocket().close();
            } catch (Exception ignored) {}
            System.out.println("[Cleanup] Đã giải phóng hoàn toàn bộ nhớ cho player: " + playerId);
        }
    }
}


Chi tiết các khái niệm & công việc con trong PDF (Giải thích cặn kẽ cho Mai):
Phân biệt 5 tình huống đứt kết nối trong thực tế:
Người chơi đóng game bình thường: Người chơi bấm nút "Thoát" hoặc dấu X. Client chủ động gửi gói tin DISCONNECT đàng hoàng lên Server rồi mới đóng Socket. Server nhận được sẽ xử lý êm đẹp (Graceful Shutdown).
Mất Wi-Fi / Rút dây mạng: Không có thông báo đóng gửi lên. Server phát hiện được thông qua cơ chế Heartbeat Timeout (như đã giải thích ở Mục 5).
Server reset connection: Đường truyền trung gian (Router/Tường lửa) ngắt ngang kết nối hoặc Server bị quá tải reset lại card mạng.
Socket bị timeout: Client bị treo cứng (Not Responding) hoặc nghẽn mạng nghiêm trọng khiến gói tin không đến được trong thời gian quy định (setSoTimeout).
Client crash đột ngột: Ứng dụng Client bị Exception crash bay màu hoặc bị người dùng bật Task Manager "End Task". Lúc này hệ điều hành của máy Client gửi ngay cờ TCP RST khiến Server ném ra lỗi Connection reset by peer.
Quy trình 4 kết quả bắt buộc phải bảo đảm khi dọn dẹp:
Session được giải phóng: Gọi socket.close(), đóng các luồng InputStream/OutputStream, xóa Session khỏi ConnectionManager. Việc này giúp hệ điều hành thu hồi Port/Socket File Descriptor, chống Memory Leak (rò rỉ bộ nhớ RAM).
Client bị xóa khỏi Room: Rút người chơi khỏi danh sách của phòng để trả lại slot trống. Nếu người thoát là Host thì chuyển quyền Host cho người kế tiếp. Nếu phòng không còn ai thì hủy phòng.
Game State được cập nhật: Báo ngay cho Tầng Game của Duy (TV2) để xóa thực thể nhân vật đó khỏi bản đồ trận đấu, tránh việc bom nổ trúng một nhân vật đã thoát hoặc Game Loop bị khựng lại chờ lệnh di chuyển.
Những người chơi còn lại nhận thông báo: Broadcast ngay bản tin PLAYER_DISCONNECTED(playerId) để màn hình của các người chơi khác lập tức biến mất nhân vật đó hoặc hiển thị thông báo "Người chơi X đã mất kết nối".

7. Cấu trúc Module Code & Sản phẩm Nghiệm thu (Theo phân công PDF)
Đây là các file và sản phẩm đầu ra thuộc trách nhiệm của bạn:
Cấu trúc thư mục mã nguồn (server/):
server/network/:
Server.java: Khởi động Server, lắng nghe ServerSocket và chạy vòng lặp accept().
ClientHandler.java: Luồng chạy riêng cho từng Client, đọc các gói tin byte thô từ Socket.
ConnectionManager.java: Quản lý danh sách kết nối của toàn Server.
server/session/:
Session.java: Chứa thông tin người chơi (playerId, username, socket, roomId, connectionState).
SessionManager.java: Quản lý cấp phát, tìm kiếm và hủy Session.
server/room/:
Room.java: Đối tượng bàn chơi 4 người, quản lý danh sách và trạng thái Ready.
RoomManager.java: Quản lý tạo phòng, vào phòng, rời phòng.
server/chat/:
ChatHandler.java: Điều hướng và broadcast tin nhắn nội bộ trong phòng.
server/heartbeat/:
HeartbeatManager.java: Quản lý gửi PING / nhận PONG và hẹn giờ phát hiện rớt mạng.


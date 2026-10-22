# Protocol tích hợp — phiên bản 2

Nhiệm vụ 9: [TASK9_STABILITY.md](TASK9_STABILITY.md) ghi giới hạn vận hành và test lỗi/stress.
Không thay schema/packet code ở lượt này; RECONNECT vẫn không đăng ký, xem
[đánh giá riêng](RECONNECT_ASSESSMENT.md).

Nhiệm vụ 8 (22/10/2026): lobby mặc định đã nối item, damage/chết, kết quả tự động và bảng kết quả.
Snapshot v4 thêm HUD/chỉ số và kết quả cuối, v5 thêm mạng/khiên/hồi sinh; client nhận v2/v3/v4/v5, M1 vẫn v2.
Luật, test TCP và giới hạn nghiệm thu tại [TASK8_ITEMS_RESULTS.md](TASK8_ITEMS_RESULTS.md).

Nhiệm vụ 7 (22/10/2026): lobby mặc định đã nối PLANT_BOMB, event bom và renderer TV3.
Xem [TASK7_CLIENT_BOMBS.md](TASK7_CLIENT_BOMBS.md). Snapshot v3 bổ sung vùng nổ còn hiển thị;
client hiện nhận v2/v3, profile M1 giữ nguyên v2. Rebuild cả server và client để chạy bản gameplay này.
Các mô tả “M2 chỉ di chuyển” dưới đây là baseline lịch sử trước nhiệm vụ 7.

Profile M2 hiện hành: [M2_LOBBY_LIFECYCLE.md](M2_LOBBY_LIFECYCLE.md). ServerApplication mặc định
đăng ký lobby nhiều phòng và movement runtime; ClientApplication mặc định mở Connect/Lobby.
LIST_ROOMS và END_GAME là bổ sung tương thích, các mã cũ/body MOVE/snapshot giữ nguyên.
Trong bản M2 chỉ di chuyển, host end hoặc bất kỳ người nào leave/disconnect sẽ hủy lượt ABORTED,
reset ready, giữ người còn lại trong phòng WAITING; không tính thắng/thua hoặc giữ ghế reconnect.

Profile M1 đã ghép: [M1_MOVEMENT_DEMO.md](M1_MOVEMENT_DEMO.md). Chạy ServerApplication với số
người 2..4 và ClientApplication với `--demo` sẽ tự admission/start khi đủ kết nối, không dùng CREATE/JOIN/READY.
Trong profile này, ROOM_STATE chờ có discriminator `profile:"MOVEMENT_DEMO"` và các field
`roomId,state:"WAITING",joined,expected`. GAME_STARTED/full/MOVE/resync vẫn dùng contract dưới đây.
Roster cố định; disconnect bỏ player khỏi demo, không nhận reconnect hoặc bù chỗ giữa trận.
Các lựa chọn B1–B6 cho game đầy đủ vẫn chưa được chốt bởi chính sách profile M1 này.

Chốt kỹ thuật ngày 21/10/2026 trong nhiệm vụ 2/M0. Đây là nguồn đặc tả chính thức cho các
bước tích hợp tiếp theo. TV4 phụ trách protocol, TV1 session/phòng, TV2 world/movement,
TV3 bom/item. Baseline này phục vụ triển khai theo yêu cầu người dùng, không phải biên bản
cả nhóm đã ký duyệt. Schema đã chốt không có nghĩa handler/bootstrap đã được nối.

Tiến độ nhiệm vụ 3: CONNECT/PING/DISCONNECT đã nối qua server thật; xem
[SERVER_TRANSPORT_STATUS.md](SERVER_TRANSPORT_STATUS.md) là báo cáo lịch sử transport. Room/movement đã đăng ký
ở launcher M2; RECONNECT/NETWORK_LOST chưa đăng ký. Server transport thuần vẫn chỉ CONNECT/PING/DISCONNECT.
SessionPacketCodec nhận object phẳng cho ba message này, giới hạn 1.024 ký tự,
không nhận field trùng/thừa, sai kiểu hoặc tên chứa control character.

## 1. Version và tương thích

- CONNECT mới dùng protocolVersion=2. snapshotVersion=2/3/4/5 là version riêng của binary body.
- Giữ nguyên mã packet cũ, body MOVE và byte layout snapshot v2. Bổ sung REQUEST_FULL_STATE=0x0034.
- Protocol 2 bắt buộc match envelope cho gameplay; unwrap rồi gọi codec chuyên biệt hiện có.
- Unscoped/protocol 1 chỉ dành cho harness/regression hoặc adapter legacy được cấu hình rõ.
  Ứng dụng tích hợp mới từ chối version khác 2 bằng UNSUPPORTED_VERSION; không đoán version từ payload.
- API deprecated vẫn giữ; GameRuntime.matchId() là accessor bổ sung, không thay chữ ký cũ.
- PROTOCOL.md tại gốc chỉ dẫn về tài liệu này; tài liệu thành viên cũ không ghi đè schema ở đây.

## 2. Frame, giới hạn và lỗi

TCP frame: [length:int32][type:int16][payload:UTF-8 JSON], big-endian.
Length tính type + payload, không tính bốn byte length: 2..1.048.576 byte.
Payload tối đa 1.048.574 byte, tính sau thêm match header/Base64. Không có newline phân cách.
PING {} có golden bytes: 00 00 00 04 00 40 7B 7D.
Decoder phải xử lý frame chia/gộp, đọc đủ body và từ chối UTF-8 sai. Một writer/socket.

Frame/length/UTF-8 lỗi: đóng kết nối có kiểm soát. JSON/field/quyền nghiệp vụ lỗi: ERROR,
không thay world. Biết packet type không đồng nghĩa được phép gửi ở mọi hướng/trạng thái.
Codec MOVE/snapshot/resync là canonical, không phải parser JSON tổng quát; client dùng encoder chung.

## 3. ID và mapping

| ID | Kiểu / nguồn | Quy tắc |
|---|---|---|
| sessionId | UUID string server cấp, hiện lưu ở Session.getPlayerId() | Ổn định trong reconnect; CONNECT_ACK giữ tên playerId cho UUID phiên |
| roomId | UUID string server cấp | Server kiểm tra membership, không tin roomId payload để vượt quyền |
| playerId gameplay | int32 dương | Chỉ có nghĩa trong match; không tái sử dụng ID người rời trong cùng trận |
| matchId | UUID từ GameRuntime.matchId() | Runtime mới luôn có UUID mới kể cả cùng room; reconnect cùng trận giữ UUID |
| bombId/itemId | int32 dương | Namespace kind + match riêng; không đổi ngầm sang long |
| sequence/tick/baseTick/requestId/eventId | int64 Java / JSON integer | Không dùng float; sequence/requestId dương, tick không âm |

MatchContext(roomId,matchId,players) giữ mapping bất biến [{sessionId:string,playerId:int}],
1–4 người, không trùng phiên hoặc số ID. DTO cho phép chuỗi không rỗng để giữ fixture room-a/session-a;
handshake production phải cấp UUID chuẩn. MOVE không chứa playerId, tọa độ hoặc tốc độ.
Server lấy ID từ session tin cậy và runtime.playerId(sessionId). Token chỉ gửi unicast chủ phiên.

## 4. Match envelope và connection generation

Protocol 2 thêm matchId làm field đầu, giữ nguyên body của codec bên trong:

```json
{"matchId":"11111111-1111-4111-8111-111111111111","sequence":7,"direction":"RIGHT","moving":false}
{"matchId":"11111111-1111-4111-8111-111111111111","snapshotVersion":2,"data":"BASE64..."}
```

MatchEnvelopeCodec.wrap/unwrap kiểm tra type, UUID dự kiến, header trùng và giới hạn frame.
Nó không xác thực JSON/field bên trong thay codec chuyên biệt. Header yêu cầu UUID canonical.
Áp dụng cho MOVE, PLANT_BOMB, GAME_SNAPSHOT, FULL_STATE_SYNC, REQUEST_FULL_STATE, END_GAME, GAME_OVER,
BOMB_PLANTED, BOMB_EXPLODE, ITEM_SPAWN, ITEM_PICKED_UP, PLAYER_DIE, PLAYER_DISCONNECTED trong trận.
GAME_STARTED tự chứa matchId, không wrap. Room/session/heartbeat không wrap.
PLAYER_DISCONNECTED ở lobby không wrap, dùng roomId + session UUID; phân theo trạng thái, không đoán payload.

Server kiểm tra session/current socket generation/PLAYING/membership/matchId trước enqueue.
Client kiểm tra matchId trước apply replica. Mỗi connect/reconnect có generation cục bộ mới;
callback reader/sender cũ không được sửa phiên mới. MatchId chưa tự chặn socket cũ trong cùng match.

## 5. Session và lobby

S là UUID phiên, R là UUID phòng, M là UUID match. Field không ghi optional là bắt buộc.
RoomManager/ChatHandler trong M2 phát schema này. Client gửi roomId cho các thao tác phòng;
server cũng nhận dạng rút gọn thiếu roomId bằng membership hiện tại. Nếu có roomId, phải khớp membership;
không cho request cũ tác động phòng khác. Request tối đa 4.096 ký tự, không field trùng/thừa hoặc sai kiểu.

| Mã / tên | Hướng | Body và ngữ nghĩa |
|---|---|---|
| 0x0001 CONNECT | C→S | {playerName:string,protocolVersion:2}; tên trim 1–32 ký tự, không control character |
| 0x0002 CONNECT_ACK | S→C unicast | {playerId:S,reconnectToken:string,serverVersion:string,protocolVersion:2,heartbeatIntervalMs:2000,heartbeatTimeoutMs:10000,reconnectGraceMs:15000} |
| 0x0010 CREATE_ROOM | C→S | {roomName:string,maxPlayers:int,mode:"STANDARD"}; tên phòng 1–64 ký tự; sức chứa 2–4; mode có thể bỏ, mặc định STANDARD, mode khác bị từ chối |
| 0x0011 JOIN_ROOM | C→S | {roomId:R}; chỉ phòng WAITING còn chỗ |
| 0x0012 ROOM_STATE | S→C/Room | {roomId:R,roomName:string,hostId:S,state:"WAITING" hoặc "PLAYING",maxPlayers:int,players:[{playerId:S,username:string,state:string}]} |
| 0x0013 READY | C→S | {roomId:R,ready:boolean}; set idempotent, không toggle; adapter xử lý nguyên tử trên room |
| 0x0014 START_GAME | C→S | {roomId:R}; host, 2–4 người, mọi khách ready; host bấm Start được coi là ready, giữ luật canStartGame hiện có |
| 0x0015 GAME_STARTED | S→Room | {roomId:R,matchId:M,tickMillis:50,inputStepMillis:50,mapId:string,players:[{sessionId:S,playerId:int,username:string}]} |
| 0x0016 LEAVE_ROOM | C→S | {roomId:R}; server unicast cùng type {roomId:R,left:true}, peer nhận ROOM_STATE |
| 0x0017 CHAT | C→S | {roomId:R,message:string}; 1–256 ký tự, server kiểm tra membership |
| 0x0017 CHAT | S→Room | {roomId:R,playerId:S,username:string,message:string}; tác giả từ session; JSON, không dùng plaintext 0x0050 |
| 0x0018 LIST_ROOMS | C→S / S→C | Request {}; response {rooms:[ROOM_STATE body...]}; gửi sau CONNECT_ACK, khi refresh và mỗi lần phòng thay đổi; tối đa 32 phòng |
| 0x0035 END_GAME | C→S | {matchId:M}; chỉ host của trận hiện tại; hủy lượt M2 và trở lại WAITING |
| 0x0040 PING | C→S | {pingId:int}; mỗi 2 giây, không tái dùng ID đang chờ |
| 0x0041 PONG | S→C | Echo đúng body PING |
| 0x0042 DISCONNECT | C→S | {reason:"USER_QUIT"}; dọn phiên ngay, không giữ ghế reconnect |
| 0x0043 RECONNECT | C→S socket mới | {playerId:S,reconnectToken:string,protocolVersion:2}; phiên DISCONNECTED, token đúng, trong hạn |
| 0x0044 NETWORK_LOST | C→S best effort | {lastSeq:long,reason:string}; không thay thế EOF/heartbeat, danh tính từ socket |
| 0x00FF ERROR | S→C | {errorCode:string,message:string,requestId:long hoặc null}; không chứa token/stack trace |

Client lấy localId bằng session UUID từ CONNECT_ACK đối chiếu players trong GAME_STARTED.
Map đầy đủ đến từ full snapshot. State của players lấy tên SessionState, không thêm token vào roster.
Reconnect thành công trả CONNECT_ACK, rồi GAME_STARTED/full nếu còn trong trận; nếu ở lobby/room
thì trả ROOM_STATE phù hợp, không tạo trận giả. Thất bại không chiếm ghế/socket không có chủ.

Mã lỗi: UNSUPPORTED_VERSION, INVALID_PACKET, UNKNOWN_MESSAGE, NOT_CONNECTED, NOT_IN_ROOM,
ROOM_NOT_FOUND, ROOM_FULL, ROOM_PLAYING, NOT_HOST, NOT_READY, NOT_PLAYING, STALE_MATCH,
INPUT_QUEUE_FULL, RATE_LIMITED, RECONNECT_REJECTED, MATCH_START_FAILED, MATCH_FAILED.
M2 thêm ALREADY_IN_ROOM, INVALID_ROOM_STATE, ROOM_LIMIT, MODE_NOT_SUPPORTED, CHAT_QUEUE_FULL.
Lỗi yêu cầu trong lobby được hiển thị để người dùng thử lại, không tự đóng socket.

LEAVE_ROOM là C→S và ACK S→C như bảng. Chat chỉ trong phòng, cả WAITING/PLAYING; không có global chat.
Danh sách phòng cho phép thấy roster nhưng không truyền token. JSON lobby tối đa 65.536 ký tự,
độ sâu 8, tối đa 256 phần tử/collection; client không render HTML từ tên/chat.
Room state sau ready/start/end và leave được phát cho thành viên hiện tại. Host rời thì người còn lại
vào sớm nhất nhận quyền host; phòng hết người bị xóa. Không join trực tiếp vào trận PLAYING.

M2 kết thúc hành chính dùng GAME_OVER theo schema mục 10: tick cuối, winnerPlayerId=null,
reason=ABORTED, rankings=[]. Không có snapshot FINISHED giả cho lượt hủy. Server dừng loop trước
khi công bố kết thúc; worker có thể còn frame đang gửi, client bỏ theo matchId hoặc trạng thái đã kết thúc.
GAME_STARTED của trận mới luôn vào writer trước worker snapshot mới. Match mới reset sequence/replica;
giữ TCP/heartbeat. Host bấm Start implicit ready; mọi khách phải READY, tổng 2–4 người.

## 6. MOVE, sequence và ACK

Body inner giữ đúng dạng hiện có:

```json
{"sequence":1,"direction":"RIGHT","moving":true}
```

- Direction UP/DOWN/LEFT/RIGHT; X sang phải, Y xuống. Player x/y/width/height là pixel,
  speed pixel/giây; nền/entity dùng tọa độ tile. TilePosition chung cũng là chỉ số ô.
- Input 50 ms (20 Hz), kể cả đứng yên. Sequence bắt đầu 1 trong match mới, tăng từng mẫu.
  Mỗi mẫu hợp lệ mô phỏng đúng một bước 50 ms, không tiếp tục chạy khi thiếu mẫu.
- moving=false giữ hướng cuối. Không gửi sequence=0 từ mạng; API legacy giữ cho demo nội bộ.
- Mẫu <= lastProcessedInput bị bỏ, khoảng trống sequence được phép theo API hiện có.
  Mỗi tick tối đa một mẫu/player, FIFO toàn cục; không reorder theo player.
- lastProcessedInput là ACK mẫu đã mô phỏng, không phải gói vừa đọc hoặc mới enqueue.
  Snapshot/ACK publish sau movement trong tick đó.
- Client bỏ pending <= ACK, đặt lại world/player authoritative rồi replay pending còn lại.
  Chết/mất mạng thì dừng input. Pending tối đa 256 theo controller hiện tại; đầy thì báo lỗi,
  không tự xóa mẫu chưa ACK để tiếp tục giả vờ đồng bộ.
- Chỉ bật input sau full có local player. Reconnect xử lý sequence theo mục lifecycle.

Giới hạn tích hợp ban đầu: MOVE 40 request/giây/connection, burst tối đa 40 trong cửa sổ 1 giây;
PLANT_BOMB thực thi 5 request mới/giây/session/match, cửa sổ 1 giây. RequestId cũ/trùng
được bỏ qua trước rate limit và không enqueue. Chỉ ghi nhận requestId sau submit thành công;
INPUT_QUEUE_FULL cho phép gửi lại cùng ID. Vị trí/playerId lấy từ session server, không nhận
playerId/toạ độ client. Vượt trả RATE_LIMITED. Đây không phải kết quả stress benchmark.

## 7. Snapshot full/delta v2

Inner canonical: {"snapshotVersion":2,"data":"<Base64>"}. Base64 là biểu diễn, không phải nén.
SnapshotPacketCodec là implementation duy nhất; TV4 quản protocol, TV2 phối hợp bảo trì.
Binary big-endian theo thứ tự:

| Thứ tự | Trường | Kiểu |
|---|---|---|
| 1 | kind full=0/delta=1; tick; baseTick | uint8, int64, int64 |
| 2 | mapWidth,mapHeight,tileSize | ba int32 |
| 3 | matchState; matchStateChanged | uint8, boolean một byte 0/1 |
| 4 | fullMapCount rồi tile | int32, uint8/tile; row-major y*width+x |
| 5 | tileChangeCount rồi x,y,newType | int32; mỗi record int32,int32,uint8 |
| 6 | playerCount rồi PlayerData | int32; full toàn bộ, delta upsert |
| 7 | removedPlayerCount rồi ID | int32; mỗi ID int32 |
| 8 | entityCount rồi EntityData | int32; full toàn bộ, delta upsert |
| 9 | removedEntityCount rồi key | int32; mỗi key writeUTF |

PlayerData: id:int32, x/y/width/height/speed:double, direction:uint8, moving/alive:boolean,
lastProcessedInput:int64. EntityData: kind:uint8,id/tileX/tileY:int32,ownerId:writeUTF,
variant:int32,expiresAtMillis:int64. ownerId là session UUID chủ bom, item dùng chuỗi rỗng.
Key xóa BOMB:<id>/ITEM:<id>; variant bom=range, item có mã cố định BOMB_UP=0/FIRE_UP=1/SPEED_UP=2/LIFE_UP=3/SHIELD=4.
writeUTF là Java modified UTF-8 với length uint16, khác UTF-8 JSON bên ngoài.
expiresAtMillis là giờ server; client cần ước lượng clock server/thời lượng trước khi vẽ countdown.
Body hiện chưa truyền quota/range player cho HUD; muốn thêm phải có version/migration, không suy ra field chưa có.

Ordinal wire cố định: Direction UP=0,DOWN=1,LEFT=2,RIGHT=3; Tile EMPTY=0,HARD_WALL=1,
BREAKABLE_BLOCK=2; MatchState WAITING=0,RUNNING=1,FINISHED=2; Entity BOMB=0,ITEM=1.
OUT_OF_BOUNDS chỉ là sentinel truy vấn, không được lưu/truyền. Không đổi ordinal/layout mà giữ version 2.

Full: baseTick=-1, mapCount=width*height, không tile changes/xóa. Delta: 0<=baseTick<tick,
không full map. Kích thước dương, số ô <=1.000.000, tổng frame vẫn vừa giới hạn; không byte thừa.
Kiểm tra tile/player/entity hợp lệ trước apply. Tick <= tick đang giữ là stale, bỏ.
Chỉ apply delta khi baseTick đúng tick hiện tại và kích thước không đổi. Thiếu baseline thì giữ
world cũ và xin full. Full thay map/player/entity; thiếu record trong delta không có nghĩa xóa.

## 8. Resync và broadcast

### Phần mở rộng snapshot v3 cho bom

Inner canonical dùng `{"snapshotVersion":3,"data":"<Base64>"}`. Giữ nguyên toàn bộ
layout v2 ở mục 7, rồi nối thêm:

| Trường | Kiểu / ý nghĩa |
|---|---|
| serverTimeMillis | int64, giờ server tại tick snapshot |
| explosionCount | int32, tối đa 256 |
| Mỗi explosion | bombId:int32, startedAtMillis:int64, visibleUntilMillis:int64, cellCount:int32 |
| Mỗi cell | x:int32, y:int32; trong map, không trùng; tối đa 1024 ô/explosion |

Danh sách explosion trong cả full và delta là **toàn bộ hiệu ứng còn hiệu lực**, không phải
delta effect. Danh sách rỗng xóa mọi lửa. Hiệu ứng dài 600 ms, chỉ để hiển thị; server damage
vẫn tức thời tại tick nổ. Adapter hết hạn hiệu ứng bằng nanoTime, client dùng thời gian còn lại
so với serverTimeMillis rồi chạy animation theo nanoTime local. Không tự xóa bom khi countdown
về 0: chờ snapshot server. Ước lượng này chưa bù độ trễ mạng một chiều.

Snapshot v2 và golden bytes cũ giữ nguyên khi không có bomb frame. Decode nhận cả v2/v3;
không đổi ordinal EntityData. Full resync chứa map, alive, bom và vùng nổ còn hiệu lực cùng tick.
Thiếu baseline thì giữ scene cũ, xin full; chỉ thay scene sau khi replica áp dụng snapshot thành công.

### Phần mở rộng snapshot v4 cho item/chỉ số/kết quả

Inner canonical dùng `{"snapshotVersion":4,"data":"<Base64>"}`. Sau toàn bộ body v3,
nối `remainingSeconds:int32`, `statsCount:int32`, mỗi stats gồm
`playerId:int32,bombLimit:int32,range:int32`; tiếp theo là `hasResult:boolean`.
Nếu hasResult=true, nối `result:writeUTF` chứa JSON inner GAME_OVER đúng schema mục 10.
Speed tiếp tục ở PlayerData; không đổi layout PlayerData v2. Cả full và delta v4 đều
mang toàn bộ HUD stats; replica xác thực danh sách ID khớp danh sách player sau update.
Result chỉ hợp lệ với snapshot FINISHED, result.tick không lớn hơn snapshot.tick.
Từ chối byte thừa, stats/record không hợp lệ; v2/v3 giữ byte layout cũ.

remainingSeconds là thời gian còn lại làm tròn lên, cập nhật khi giây hiển thị thay đổi;
deadline thực tế xét bằng nanoTime trên server, không dựa vào timer client.
Snapshot cuối luôn là full, được gửi trước event và GAME_OVER. Client ứng dụng apply snapshot
trực tiếp trên UI owner trước khi xử lý GAME_OVER để không mất final state do callback queue.

### Phần mở rộng snapshot v5 cho mạng/khiên/hồi sinh

Inner canonical dùng `{"snapshotVersion":5,"data":"<Base64>"}`. Giữ layout v3;
phần MatchFrame giống v4 nhưng mỗi Stats là:
`playerId:int32,bombLimit:int32,range:int32,lives:int32,shield:boolean,respawns:int32`.
Các trường result phía sau giữ nguyên. Cả full và delta mang toàn bộ danh sách Stats.
Lives/respawns không âm; mã ItemType cố định, không lấy ordinal khi encode/decode item.
Client mới vẫn đọc v4 với lives=1/shield=false/respawns=0; server lobby mới phát v5.
Client dùng respawns để bỏ pending prediction/nội suy trước hồi sinh mà không lùi sequence.
Client cũ không hỗ trợ v5: cập nhật đồng thời server và client.

### Yêu cầu full state

REQUEST_FULL_STATE 0x0034 C→S, có match envelope; inner:

```json
{"lastAppliedTick":-1,"reason":"COLD_START"}
```

Reason còn có BASELINE_MISMATCH/RECONNECT, lastAppliedTick>=-1; -1 là chưa có baseline.
Một connection chỉ một request đang chờ, tối đa một/giây. Không tin tick client để rewind world.
FULL_STATE_SYNC 0x0033 S→C/Room dùng cùng body snapshot v2 và bắt buộc full, có match envelope.
SnapshotPacketCodec.encodeFullSync tạo response; decode từ chối delta trong FULL_STATE_SYNC.

Handler validate membership/match → requestFullState → consumer duy nhất chờ full trong queue.
Không dùng createUpdate làm full; không thêm consumer tranh queue để trả riêng một client.
Baseline dùng chung room nên full do resync gửi mọi client đang chơi; full định kỳ có thể dùng
GAME_SNAPSHOT, full theo yêu cầu dùng FULL_STATE_SYNC. Client đăng ký cả hai type.

Một consumer/runtime đọc pollUpdate theo FIFO, encode/wrap một lần rồi enqueue các writer.
Writer đầy: đóng riêng client chậm/cleanup; không bỏ delta rồi gửi delta sau trên baseline đã mất.
Không socket write trên GameLoop/EDT. Nếu outbound GameLoop đầy, builder không commit bản chưa publish,
update tiếp dựa baseline đã publish; giữ cơ chế hiện có, không dùng latest như thay thế chuỗi delta.

## 9. Heartbeat và lifecycle

Client PING mỗi 2 giây, server echo PONG; timeout 10 giây, grace reconnect 15 giây.
Server sweep mỗi 2 giây nên timeout có thể muộn thêm một chu kỳ. Packet decode và được chấp nhận
có thể cập nhật liveness; byte rác chưa decode không được coi heartbeat. Client timeout khi không
nhận phản hồi hợp lệ 10 giây. Dùng nanoTime cho khoảng thời gian, expire/đếm ping timeout,
pending ping không tăng vô hạn. RTT không phải latency một chiều.

### Start/rollback

1. Khóa room WAITING, xác thực host/2–4 người/các khách ready, chụp roster và cấp ID/spawn.
   Host được coi ready khi bấm Start, không bắt thêm thao tác READY trái canStartGame hiện có.
2. Tạo runtime/map/extension riêng và matchId, gắn consumer/context nhưng chưa phát snapshot.
3. Commit room/session PLAYING; enqueue GAME_STARTED rồi full đầu qua cùng writer FIFO.
4. Client gắn listener thường trực trước START, dựng context/replica mới khi GAME_STARTED,
   apply full có localId rồi bật input. Buffer chờ EDT nếu cần phải có giới hạn, không làm mất full đầu.
5. Tạo trận thất bại: stop runtime/worker mới, rollback trạng thái trước start, MATCH_START_FAILED.
   Start lặp không tạo hai runtime. M2 thực hiện giao dịch này trong LobbyGameServer, không gọi
   RoomManager.startGame legacy vì phương thức đó chỉ chuyển trạng thái/phát metadata cũ.

### Disconnect/reconnect

Các bước reconnect dưới đây là hợp đồng mở rộng, chưa nối vào launcher M2.
M2 hiện hủy lượt/bỏ ghế ngay khi disconnect, gửi GAME_OVER và ROOM_STATE cho người còn lại;
không áp dụng giữ ghế 15 giây cho phòng. Transport chỉ giữ session tạm tới khi hard drop.

1. Vô hiệu hóa generation socket cũ trước, không cho enqueue thêm; đóng socket.
2. Enqueue disconnect trên cùng FIFO sau command cũ đã được chấp nhận; game thread dừng movement.
   Mất mạng giữ ghế 15 giây; USER_QUIT remove/dọn ngay.
3. Token đúng trong hạn: giữ session/playerId/matchId, thay connection generation.
   Chỉ enqueue reconnect sau barrier disconnect và command cũ đã xử lý/bỏ.
4. ReconnectedCommand mở player/yêu cầu full; consumer chọn full sau barrier.
   Handler chặn input mới tới khi full đã được enqueue cho socket mới; trước đó trả NOT_READY.
5. Client hủy sender/pending/prediction cũ, dựng mới từ full, gửi sequence=ACK+1 sau khi apply full.
   Không reset ACK server, không replay pending connection cũ. TCP writer mới bảo đảm thứ tự.
6. Không cần thêm ACK-full message: server mở cửa nhận MOVE sau enqueue full; client chỉ gửi
   sau nhận/apply. Reconnect cùng trận vẫn phải kiểm tra generation, không chỉ matchId.
7. Hết hạn/remove: dọn timer/ghế/runtime theo trạng thái; callback socket cũ đóng muộn không dọn socket mới.

### Kết thúc/hủy

Game thread phát FINISHED/kết quả một lần, snapshot cuối + GAME_OVER. Người tích hợp dừng command,
dừng/join loop, đóng cleanup handle extension/scheduler/executor thuộc trận và worker, reset room/session.
GameplayExtension có onStart/onStop cùng game thread; GameLoop gọi onStop trong finally,
kể cả lỗi callback. Bom tích hợp dùng deadline, không tạo scheduler/executor riêng.
Runtime failure báo MATCH_FAILED và dọn cùng đường, không tiếp tục delta. Trận mới có matchId/replica/pending mới.

## 10. Bom/item/event cho M3

Bảng dưới là contract chính thức; bom đã nối ở nhiệm vụ 7, item/death/GAME_OVER đã nối ở nhiệm vụ 8.
Event có match envelope,
eventId tăng theo match, tick và occurredAtMillis server cấp; snapshot là world authoritative,
event chỉ hiệu ứng/thông báo, không cộng chỉ số/giết/nhặt lần thứ hai.

| Mã / message | Body inner chính |
|---|---|
| 0x0021 PLANT_BOMB C→S | {requestId:long}; dương, tăng theo match/player; duplicate không đặt thêm; vị trí từ player server |
| 0x0022 BOMB_PLANTED | {eventId:long,tick:long,occurredAtMillis:long,bombId:int,ownerPlayerId:int,x:int,y:int,fuseMs:long,explodeAtMillis:long,range:int} |
| 0x0023 BOMB_EXPLODE | {eventId:long,tick:long,occurredAtMillis:long,bombId:int,x:int,y:int,affectedCells:[{x:int,y:int}]} |
| 0x0024 ITEM_SPAWN | {eventId:long,tick:long,occurredAtMillis:long,itemId:int,itemType:"BOMB_UP" hoặc "FIRE_UP" hoặc "SPEED_UP" hoặc "LIFE_UP" hoặc "SHIELD",x:int,y:int} |
| 0x0025 ITEM_PICKED_UP | {eventId:long,tick:long,occurredAtMillis:long,itemId:int,playerId:int,itemType:string} |
| 0x0026 PLAYER_DIE | {eventId:long,tick:long,occurredAtMillis:long,playerId:int,killerPlayerId:int hoặc null,cause:string} |
| 0x0031 GAME_OVER | {tick:long,winnerPlayerId:int hoặc null,reason:"LAST_ALIVE" hoặc "ALL_DEAD" hoặc "TIMEOUT" hoặc "ABORTED",rankings:[{playerId:int,rank:int}]} |
| 0x0032 PLAYER_DISCONNECTED trong trận | {playerId:int,reason:"USER_QUIT" hoặc "NETWORK_LOST" hoặc "TIMEOUT",temporary:boolean} |

Trong lobby, disconnect dùng {roomId:R,playerId:S,reason:string,temporary:boolean} không match header.
Không cấp packet BLOCK_DESTROYED mới: thay đổi map theo snapshot; event nội bộ dùng sinh item/log.
BombPlacedEvent Java ánh xạ BOMB_PLANTED wire. World/event TV3 dùng String session UUID;
adapter chuyển int gameplay cho event wire, EntityData.ownerId giữ UUID; không parse String thành int.

BombPacketCodec hiện encode/decode đúng hai schema event bom ở bảng trên. eventId cấp tăng
trên game thread cho từng match; worker chỉ serialize bản sao bất biến sau snapshot cùng tick,
qua cùng writer queue. Backlog event tối đa 4096; chỉ xóa sau khi snapshot enqueue thành công.
Không phát lại event trong full resync: snapshot v3 tự dựng trạng thái/hiệu ứng hiện tại.
Client xác thực schema/match, chỉ ghi nhận event cùng tick replica và dedupe eventId (cache 256);
event cũ, tương lai hoặc trùng không sửa scene/world. Snapshot mới là nguồn render bom/lửa.
Map chỉ thay qua snapshot; event không gây damage, không phá block và không khởi động lại animation.

Model chung cho đường tích hợp là game/model; các kiểu legacy trong package bom không được
dùng trên đường runtime/protocol/renderer hiện tại. TV2 sở hữu adapter GameState, TV3 extension/system,
TV4 codec. World chỉ sửa trên game thread; scheduler chỉ enqueue, chọn một cơ chế hết hạn bom.
Client không tự tính damage, pickup hay kết quả. Luật cân bằng chờ lựa chọn riêng, không chặn M1.

### Luật gameplay hiện hành sau bổ sung item/mạng

`MatchGameplayExtension` xử lý một batch bom/damage hoàn chỉnh trước khi xét thắng/hòa,
không tuyên bố thắng khi mới chết một người trong batch còn đang xử lý. Sau blast: xóa item
cũ trong vùng nổ → sinh drop từ block vừa phá → pickup → quyết định kết quả. Drop mới
không bị các tia trong cùng batch xóa lần nữa. Không có item đầu trận. Drop chỉ từ block mềm, mặc định 75%, năm loại
chia đều; policy/RNG có thể inject để test. Item không có hạn tự biến mất.

Pickup tự động theo tile chứa tâm hitbox server, chỉ player sống. Khi tranh cùng item trong
cùng tick, playerId số nhỏ hơn được ưu tiên; item tiêu thụ một lần, không dựa vào thứ tự packet.
BOMB_UP tăng quota 1 (trần 5), FIRE_UP tăng range 1 (trần 8), SPEED_UP tăng 8 pixel/s
(trần 144), LIFE_UP tăng mạng 1 (trần mặc định 99), SHIELD chặn một đợt damage và không stack.
Quota/range ban đầu từ BombRules (mặc định 1/1), speed ban đầu 80; caps phải >= chỉ số ban đầu.
Lobby căn hitbox 24×24 giữa ô 32×32; tọa độ ô vẫn theo tâm, không thay layout snapshot.
Bom đã đặt giữ range lúc đặt; chỉ số mới ảnh hưởng lần đặt/di chuyển sau pickup.

Damage tức thời một lần tại tick nổ theo hitbox overlap; chạm mép không trúng, không miễn
damage cho chủ bom. Chain/ray cùng tick gộp một hit/người; còn mạng thì hồi sinh tại spawn ban đầu,
giữ upgrades, dừng movement. Mặc định 3 mạng. Khiên chặn cả hit đó; đợt nổ ở tick sau vẫn gây damage.
Hết mạng mới bị loại và phát PLAYER_DIE; người bị loại dừng movement/plant/pickup. Một người còn sống: LAST_ALIVE;
không ai sống: ALL_DEAD; hết 180 giây mà còn >=2 người: TIMEOUT, hòa. Xét alive trước timeout
nếu cùng tick. Xếp hạng theo thời điểm bị loại, người sống hoặc chết cùng tick đồng hạng;
rank = 1 + số người sống lâu hơn. GAME_OVER có winnerPlayerId chỉ khi LAST_ALIVE.

Host End/Leave hoặc disconnect vẫn hủy lượt ABORTED theo M2, không tính thắng/thua và
rankings rỗng. Nếu đã có kết quả server công bố trước thao tác này thì giữ kết quả đó.
Đây là lựa chọn tiếp tục chính sách hiện có; chưa bật reconnect/giữ ghế hay xử thua cá nhân.
Kết thúc tự động hoặc hủy đều dọn runtime/bom/item/worker và reset ready; trận sau dùng
matchId, state, RNG, item/bomb/event ID và controller mới.

GameNotice dùng chung sequence eventId cho bom/item/death. GameplayPacketCodec encode
và validate đúng schema; event chỉ thông báo, snapshot mới thay entity/alive/chỉ số.
Nhận event pickup/death lặp hoặc đến trước snapshot không cộng chỉ số, xóa item hay giết lần nữa.
GAME_OVER trùng/cũ bị bỏ theo context/match; client chỉ hiển thị kết quả server, không suy ra winner.

## 11. Kiểm chứng và phần còn lại

IntegrationContractTest khóa golden frame/snapshot, match envelope, mapping, resync/full-sync,
malformed/oversize và match epoch sau chơi lại. Nhiệm vụ 3–9 đã bổ sung test TCP, gameplay,
heartbeat/queue/fault và stress start/stop. GUI trực tiếp, LAN/soak dài hạn và reconnect
chưa được nghiệm thu chỉ dựa trên các test cục bộ này.

Xem [M0_INTEGRATION_CONTRACT.md](M0_INTEGRATION_CONTRACT.md) cho quyết định kỹ thuật,
bàn giao từng thành viên và những lựa chọn nghiệp vụ cần người dùng xác nhận.

## 12. Chính sách ổn định sau nhiệm vụ 9

- Trước route, ClientHandler đếm mọi frame decode được, kể cả unknown/rejected:
  tối đa 100/giây/connection. Vượt trả RATE_LIMITED và đóng sau drain hữu hạn.
  CHAT/LIST_ROOMS mỗi loại 5/giây; vượt loại riêng trả RATE_LIMITED, chưa đóng nếu chưa vượt tổng.
  Các giới hạn MOVE 40, PLANT_BOMB 5 và resync 1 s vẫn áp dụng đồng thời.
- RuntimeException ngoài validation trong handler trả SERVER_ERROR rồi đóng đúng captured
  connection, ghi diagnostics. Validation sai giữ INVALID_PACKET, version sai giữ UNSUPPORTED_VERSION.
- Writer server có 256 frame/4 MiB gồm frame đang ghi, deadline ghi/flush 5 s;
  overflow/timeout đóng socket và cleanup bất đồng bộ. Không drop delta riêng lẻ để tiếp tục sai baseline.
- Frame đọc có deadline tuyệt đối 10 s, không gia hạn theo từng byte; pending heartbeat hết hạn
  hard drop, session đã handshake disconnect và giữ metadata tối đa 15 s. Ghế/trận không được giữ.
- Client pull/UI có budget 4 MiB và count hữu hạn; overflow/listener lỗi phải dừng và báo lỗi,
  không âm thầm bỏ packet. Client có write deadline 5 s; close gỡ listener và dừng các worker thuộc nó.
- Retained session tối đa 256, reader pool 32 + queue 32. Xem bảng đầy đủ trong báo cáo ổn định;
  capacity là giới hạn cấu hình, chưa phải kết quả benchmark ở tải trần.
- RECONNECT chưa có handler; API legacy SessionManager.reconnect không chứng minh resume
  an toàn cho room/world, timer/socket generation hoặc sequence/ACK. Nếu cần, triển khai ở lượt riêng.

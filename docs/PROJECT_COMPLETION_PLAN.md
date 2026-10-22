# Kế hoạch hoàn thành Bomb It Multiplayer

Bổ sung sau bàn giao: [5 item, mạng/hồi sinh và khiên](EXTENDED_ITEMS_LIVES.md),
snapshot v5, range mặc định 1, 3 mạng, drop từ block 75%; 123 test đạt trên JDK 25.
Đã kiểm thử hai client TCP và replay/resync; GUI/LAN và cân bằng chơi thực tế còn mở.

Nhiệm vụ 10 (22/10/2026): [TASK10_HANDOVER.md](TASK10_HANDOVER.md) ghi công cụ đo,
CSV thực tế 1/2/4 client, script build/test/package, hướng dẫn LAN, báo cáo và kịch bản demo.
112 test đạt; 9 kịch bản loopback/25 dòng client và smoke đa tiến trình cục bộ đã chạy.
M4/M5 còn phần nghiệm thu GUI/LAN nhiều máy và soak do nhóm thực hiện; không đánh dấu đạt
chỉ từ code hoặc số liệu loopback. Xem checklist bằng chứng trong báo cáo bàn giao.

Nhiệm vụ 9 (22/10/2026): [TASK9_STABILITY.md](TASK9_STABILITY.md) ghi heartbeat,
queue/rate/deadline, fault isolation và cleanup; 108 test đạt, gồm stress 20 trận/10 server cycle.
Reconnect [được đánh giá riêng](RECONNECT_ASSESSMENT.md), chưa triển khai trong ứng dụng.
Soak test/LAN/GUI trực tiếp còn mở; bước tiếp theo là đo mạng và bàn giao ở nhiệm vụ 10.

Nhiệm vụ 8 (22/10/2026): [TASK8_ITEMS_RESULTS.md](TASK8_ITEMS_RESULTS.md) ghi item spawn/pickup,
chỉ số, damage/chết, thắng/hòa/timeout, GAME_OVER/bảng kết quả và 89 test hồi quy đạt.
Đã kiểm chứng tranh item, cùng chết, disconnect và chơi lại qua test server/TCP;
nghiệm thu Swing trực tiếp/LAN và ổn định dài hạn vẫn mở, chưa đánh dấu toàn bộ bàn giao hoàn tất.

Nhiệm vụ 7/client bom (22/10/2026): [TASK7_CLIENT_BOMBS.md](TASK7_CLIENT_BOMBS.md)
ghi protocol PLANT_BOMB/event, snapshot v3/full recovery, renderer TV3, test hai client TCP
và 77 test hồi quy đạt. Lobby mặc định đã có bom; GUI trực tiếp/LAN và nhiệm vụ 8 còn mở.

Nhiệm vụ 6/server bom (22/10/2026): [TASK6_SERVER_BOMBS.md](TASK6_SERVER_BOMBS.md)
ghi luồng extension/adapter, checklist, 13 test bom và kết quả 69 test hồi quy đạt.
Đã kiểm chứng runtime/TCP; packet/phím đặt bom, renderer và bootstrap gameplay thuộc nhiệm vụ 7.

Nhiệm vụ 5/M2: [M2_LOBBY_LIFECYCLE.md](M2_LOBBY_LIFECYCLE.md) ghi file thay đổi, test TCP/controller,
chính sách hủy lượt/reset ready, hướng dẫn lobby và checklist thực tế. Lobby/runtime đã ghép;
nghiệm thu thao tác Swing và nhiều máy LAN vẫn chưa hoàn tất.

Nhiệm vụ 4/M1: [M1_MOVEMENT_DEMO.md](M1_MOVEMENT_DEMO.md) có bản chạy, lệnh demo,
test TCP/đa tiến trình và checklist nghiệm thu GUI/LAN còn lại.

Kết quả kiểm tra nhiệm vụ 1 (21/10/2026), checklist thực tế và các API còn mâu thuẫn:
[PROJECT_STATUS_AUDIT.md](PROJECT_STATUS_AUDIT.md).

Baseline nhiệm vụ 2/M0: [M0_INTEGRATION_CONTRACT.md](M0_INTEGRATION_CONTRACT.md),
đặc tả hiện hành tại [protocol.md](protocol.md). Những đề xuất kỹ thuật cũ bên dưới được
đối chiếu theo baseline này; luật gameplay B1–B6 còn chờ người dùng lựa chọn.

Tiến độ nhiệm vụ 3 (server transport, chưa gameplay/lobby):
[SERVER_TRANSPORT_STATUS.md](SERVER_TRANSPORT_STATUS.md), có checklist và kết quả test thực chạy.

Tài liệu này là kế hoạch đề xuất cho Mai (TV1), Chinh (TV2), Duy (TV3), Giang (TV4),
dựa trên mã nguồn và phân quyền hiện có. Những việc ghi “cần làm” bao gồm cả hoàn thiện,
kiểm tra và tích hợp phần đã có; không có nghĩa phải viết lại module từ đầu.
Các phân công tích hợp/UI bổ sung bên dưới cần được nhóm xác nhận trước khi sửa module chung.

## 1. Sản phẩm cuối cùng và thứ tự ưu tiên

Sản phẩm cần đạt: game Java client/server qua TCP, 2–4 người/phòng, có tạo/join phòng,
ready/start, chat, di chuyển, va chạm, đặt bom/nổ/phá block, item, chết/thắng/thua,
phát hiện mất kết nối, hướng dẫn chạy và báo cáo đo mạng.

| Mốc | Kết quả phải chạy được | Điều kiện chuyển mốc |
|---|---|---|
| M0 — Chốt hợp đồng | Một bộ quy ước ID, protocol, luồng cập nhật và người tích hợp | Các thành viên thống nhất tài liệu và API dùng chung |
| M1 — Di chuyển multiplayer | Một server và hai tiến trình client trong một phòng cố định | Hai người điều khiển riêng, cùng thấy vị trí đúng, chạy qua TCP thật |
| M2 — Lobby và vòng đời trận | Tạo/join/ready/start/leave/chat từ giao diện | Phòng đầy bị từ chối, hai phòng độc lập, start và cleanup đúng |
| M3 — Gameplay đầy đủ | Bom, nổ, phá block, item, chết và kết quả trận | Mọi client thống nhất kết quả do server quyết định |
| M4 — Ổn định và đo mạng | Test lỗi, LAN, 1/2/4 client, số liệu và log | Không lỗi nghiêm trọng, số đo tái lập được |
| M5 — Bàn giao | Bản chạy, README, báo cáo, video/kịch bản demo | Máy khác có thể làm theo hướng dẫn và chạy được |

Reconnect tự động là hạng mục mở rộng sau M3, trừ khi rubric môn học yêu cầu bắt buộc.
Disconnect và dọn tài nguyên là bắt buộc ngay từ M1. Nhóm phải đối chiếu rubric thực tế
trước khi chốt phạm vi; kế hoạch này không thay thế yêu cầu của giảng viên.

## 2. Hiện trạng cần hiểu đúng

| Phần | Quan sát trong repository | Việc phải xác minh/hoàn thiện |
|---|---|---|
| Network/session/room | Có Server, ClientHandler, SessionManager, RoomManager, writer/broadcaster | ClientHandler hiện đọc byte và cập nhật heartbeat, chưa nối decoder/router |
| Movement/runtime | Có command queue, GameLoop, runtime manager, snapshot, prediction/reconciliation/interpolation | Nối lifecycle phòng và broadcast vào ứng dụng thật |
| Client gameplay | Có MovementGameWindow và MovementClientController | Có launcher, nhận local ID, chờ full đầu và chuyển màn hình đúng |
| Bom/item | Có system, world interface, scheduler, event, renderer và test | Nối world adapter, extension, packet/event và màn hình gameplay chung |
| Protocol | Có PacketEncoder/Decoder, PacketType, MessageRouter, ClientNetwork | docs/protocol.md còn DRAFT; thống nhất với codec và mọi handler |
| Khởi động | ServerApplication và ClientApplication mới in thông báo khung | Cần composition root thật để lắp ghép các module |
| Kiểm thử | Có test theo module, bộ check TV2, test tích hợp/benchmark | Rà từng test, chạy toàn bộ; không coi sự tồn tại của file là bằng chứng đã đạt |

GameDebugWindow là demo nội bộ cùng tiến trình, không đủ làm bằng chứng multiplayer.
Kiểm thử TCP tự động cũng cần được bổ sung bằng hai cửa sổ client chơi thật.

## 3. Phân quyền và phần chung còn thiếu người sở hữu

Giữ ranh giới trong [TEAM_OWNERSHIP.md](TEAM_OWNERSHIP.md). Các tên lớp mới trong
kế hoạch là vai trò đề xuất, không phải API đã tồn tại.

| Người | Sở hữu chính | Bàn giao cho nhóm |
|---|---|---|
| TV1 — Mai | server/network, session, room, chat, heartbeat, server/integration | API session/phòng, gửi unicast/broadcast, lifecycle kết nối |
| TV2 — Chinh | server/game/model, core, movement, runtime; client/game/movement | API trận đấu, command/snapshot, gameplay window và đồng bộ movement |
| TV3 — Duy | server/game/bomb, item, event; client/game/bomb, item | Luật bom/item, extension gameplay, event và renderer |
| TV4 — Giang | common/protocol, server/dispatch, client/network, metrics, đặc tả protocol | Codec/router, luồng nhận/gửi client, thống kê và test mạng |

Đề xuất TV4 làm người tích hợp, giữ `server/app` và `client/app`; TV1 cung cấp callback
phòng/session, TV2 cung cấp runtime/cửa sổ, TV3 cung cấp extension/renderer. Nếu TV4 quá tải,
nhóm chỉ định một người khác, nhưng vẫn chỉ có một đầu mối sửa bootstrap trong mỗi mốc.

Phân công UI bổ sung đề xuất: TV1 làm controller và view lobby trong `client/lobby`;
TV2 giữ phần màn hình trận và bảng kết quả; TV3 giữ lớp hiệu ứng bom/item;
người tích hợp giữ điều hướng connect → lobby → game → kết quả/lobby trong `client/app`.
Các view gọi ClientNetwork qua contract TV4, không mở socket riêng.

## 4. M0 — Chốt nền tảng trước khi ghép

Thực hiện theo thứ tự dưới đây. Chốt bằng một PR/tài liệu có ví dụ dữ liệu, không chỉ trao đổi miệng.

| Bước | Việc phải chốt | Chủ trì / phối hợp | Đầu ra và tiêu chí xong |
|---|---|---|---|
| 0.1 | Java 25, Maven, lệnh build/test và cấu hình host/port | TV4 / cả nhóm | Mỗi máy build được cùng revision; ghi phiên bản công cụ |
| 0.2 | Quyền sửa từng thư mục, bootstrap và UI | Cả nhóm | Ghi rõ người tích hợp; xử lý mâu thuẫn tài liệu cũ theo TEAM_OWNERSHIP |
| 0.3 | UUID session, ID int nội bộ trận, roomId và matchId/epoch | TV1 / TV2 + TV4 | Client biết localId do server cấp; MOVE không được tự khai danh tính |
| 0.4 | Frame, schema, giới hạn, phiên bản và mã lỗi | TV4 / cả nhóm | Cập nhật docs/protocol.md, ví dụ encode/decode cho từng message dùng ở M1 |
| 0.5 | Start, full đầu, resync, kết thúc và disconnect | TV1 / TV2 + TV4 | Sơ đồ thứ tự và cách rollback nếu khởi tạo trận thất bại |
| 0.6 | Tọa độ pixel/tile, hitbox, tốc độ, spawn và va chạm | TV2 / TV3 | Dùng model chung; không còn hai bộ quy tắc movement/bomb mâu thuẫn |
| 0.7 | Luật bom/item/thắng/thua | TV3 / TV2 + cả nhóm | Bảng luật: fuse/range, chain reaction, drop rate, thời gian trận, hòa |

Các điểm bắt buộc kiểm tra khi chốt protocol:

- MOVE hiện dùng `sequence`, `direction`, `moving`; một số tài liệu cũ dùng `inputSeq`.
  Chọn một schema chính thức và cập nhật đồng thời tài liệu, codec, test; không để hai cách hiểu.
- Snapshot hiện có tick/baseTick, ACK input, full/delta và entity; xác định cách gắn matchId
  hoặc cách cô lập phiên tương đương để packet trận trước không lọt sang trận sau.
- Snapshot TV2 đang bọc binary Base64 trong JSON; TV4 duyệt cách này hoặc lập migration có version.
  Giữ một codec chính thức cho mỗi message, không tự viết thêm bản khác ở ClientHandler.
- Chọn message cho yêu cầu resync và phản hồi full; hướng gửi, field, giới hạn tần suất phải rõ.
- Reconnect chỉ dựa vào playerId không đủ chứng minh quyền sở hữu phiên; nếu làm, cần token
  hoặc cơ chế xác thực tương đương, thời hạn và quy tắc vô hiệu hóa kết nối cũ.
- Chọn heartbeat do bên nào chủ động, chu kỳ, timeout và cách phân biệt heartbeat với đo RTT.

## 5. M1 — Demo di chuyển multiplayer trước

Phạm vi: một server, một phòng cố định, bản đồ cố định, hai client độc lập; sau đó tăng lên bốn.
Chưa phụ thuộc lobby hoàn chỉnh hay bom/item. Luồng join tối giản vẫn phải đi qua session hợp lệ.

### 5.1. TV1 làm theo thứ tự

1. Cung cấp tra cứu session từ kết nối tin cậy; cấp UUID server-side, kiểm tra tên/version kết nối.
2. Cùng TV4 nối ClientHandler → PacketDecoder → MessageRouter. TV1 giữ vòng đời socket;
   TV4 giữ framing/routing. Không đọc rồi bỏ byte như đoạn tạm hiện tại.
3. Cung cấp gửi một packet cho một session và broadcast trong đúng phòng qua writer queue.
   Bảo đảm một writer/connection, không trộn byte do nhiều thread cùng ghi socket.
4. Tạo luồng vào phòng demo: kiểm tra giới hạn, chốt danh sách người chơi, gán roomId;
   bàn giao danh sách session cho người tích hợp tạo PlayerSpawn.
5. Sau khi runtime khởi tạo thành công, chuyển session sang PLAYING theo thứ tự đã chốt.
   Nếu tạo trận thất bại thì rollback phòng/session và trả ERROR.
6. Nối disconnect vào command của TV2. Khi phòng rỗng, báo dừng runtime/worker;
   không sửa Player/GameState trực tiếp từ thread socket.

Nghiệm thu TV1: hai kết nối nhận hai danh tính riêng; không thể gửi MOVE thay người khác;
ngắt một client không làm chết server; writer queue và tài nguyên được dọn.

### 5.2. TV2 làm theo thứ tự

1. Chốt map/spawn không chồng, không trong tường; cung cấp cấu hình PlayerSpawn cho 2–4 người.
2. Bàn giao `GameRuntimeManager.startGame`, `findGame`, `stopGame`, `runtime.playerId`,
   `submit`, `pollUpdate`, `requestFullState` và cách báo runtime.failure().
3. Giữ MovementHandler chỉ chuyển input hợp lệ thành MoveCommand/StopMovingCommand;
   không dùng playerId từ payload, không cập nhật world từ socket thread.
4. Bàn giao MovementGameWindow nhận ClientNetwork đã kết nối và localId server cấp.
   Chỉ bật input khi đã nhận full hợp lệ và dựng xong prediction/world.
5. Kiểm tra nhấn/nhả phím, đổi hướng, mất focus, đứng yên, va chạm và ACK/replay.
   Khi lỗi/mất mạng phải dừng sinh input, tránh tăng vô hạn lịch sử chưa ACK.
6. Nối callback resync của client với message đã thống nhất; full thay baseline,
   bỏ snapshot cũ và không áp dụng delta khi thiếu baseline.

Nghiệm thu TV2: local di chuyển phản hồi ngay; remote hiển thị mượt; vị trí server là chuẩn;
không xuyên tường, không kẹt phím khi mất focus; hai client thấy cùng trạng thái cuối.

### 5.3. TV4 làm theo thứ tự

1. Hoàn tất giải mã TCP: đọc đủ frame, chấp nhận frame bị chia/gộp, từ chối length/type/payload sai.
2. Đăng ký CONNECT/join demo, MOVE, heartbeat và resync trong router bằng handler đã thống nhất.
3. Trong bootstrap server, tạo đúng một GameRuntimeManager dùng chung với MovementHandler.
4. Lắp worker đọc snapshot: đúng một consumer `pollUpdate` cho mỗi runtime,
   encode một lần rồi broadcast tới phòng theo thứ tự. Không đọc cùng queue riêng cho từng client.
5. Bảo đảm GAME_STARTED/metadata tới trước full đầu; listener client sẵn sàng trước khi nhận full.
   Có thể dùng listener thường trực và buffer/handshake; phải có test cho thứ tự này.
6. Hoàn thiện ClientApplication: host/port/name, kết nối, nhận UUID/localId/room/match,
   mở MovementGameWindow trên EDT. Cấu hình qua tham số hoặc UI, không hardcode IP từng máy.
7. Quy định queue đầy/client đọc chậm: không chặn GameLoop, không âm thầm bỏ delta rồi
   tiếp tục cùng baseline. Dùng resync có kiểm soát hoặc đóng client chậm theo chính sách đã chốt.

Nghiệm thu TV4: một lệnh chạy server và một lệnh chạy mỗi client; không gọi send chặn trên EDT;
không giữ socket write trong GameLoop; hai tiến trình client thật cùng điều khiển được.

### 5.4. TV3 làm song song

1. Hoàn thiện test bom/item độc lập và bảng luật M0, chưa chèn bom vào nhánh demo M1.
2. Rà các kiểu trùng `bomb.Direction`, `bomb.TilePosition`, `bomb.TileType` với model chung.
   Chuyển sang model chung hoặc đặt adapter rõ ràng; TV3 sửa module mình, TV2 hỗ trợ contract.
3. Chốt với TV2 world interface/extension cần dùng ở M3; liệt kê field player/entity còn thiếu.
4. Chuẩn bị renderer dùng cùng tileSize/mapOffset và nhận dữ liệu server, không tự tính damage.

### 5.5. Bài kiểm tra chung trước khi đóng M1

- Chạy server và hai client dưới dạng tiến trình riêng; mỗi người đi/đứng/đổi hướng độc lập.
- Chạy bốn client; đóng một client đang giữ phím; người còn lại tiếp tục chơi.
- Chạy ít nhất hai máy cùng LAN, nhập IP server; phân biệt lỗi firewall/IP với lỗi ứng dụng.
- Gửi MOVE sai hướng/sequence hoặc trước khi PLAYING: từ chối có kiểm soát.
- Bỏ full đầu hoặc cố ý gây sai baseline trong test: client yêu cầu full và phục hồi.
- Lưu revision, log server/client và video ngắn. Không ghép bom khi M1 chưa đạt.

## 6. M2 — Lobby, phòng và vòng đời trận hoàn chỉnh

Đối chiếu nhiệm vụ 5: [báo cáo/checklist M2](M2_LOBBY_LIFECYCLE.md).

- [x] 2.1/2.2: handler phòng, host, capacity, ready/start, rollback được kiểm tra qua TCP.
- [x] 2.4/2.5: end/leave/disconnect, chuyển host, reset ready và chơi lại với runtime/controller mới qua TCP.
- [x] Hai phòng cách ly chat/snapshot, start đồng thời và cleanup được kiểm tra tự động.
- [ ] 2.3: giao diện đã triển khai/build được; còn nghiệm thu thao tác Swing và LAN.

| Bước | TV1 | TV2 | TV3 | TV4 / người tích hợp |
|---|---|---|---|---|
| 2.1 | CREATE/JOIN/LEAVE, quyền host, tối đa bốn người, ROOM_STATE | Kiểm tra spawn theo danh sách phòng | Kiểm tra yêu cầu map gameplay | Chốt codec và đăng ký handler room |
| 2.2 | READY/START, khóa danh sách khi start, rollback khi lỗi | Tạo runtime một lần/phòng; map/state riêng | Xác nhận dữ liệu khởi tạo bom/item cho mỗi trận | Lắp lifecycle start và gửi GAME_STARTED/full |
| 2.3 | View/controller lobby: danh sách, ready, start, chat, lỗi | Bàn giao game window/close callback | Bàn giao tài nguyên hình ảnh cần thiết | Điều hướng connect/lobby/game trên EDT |
| 2.4 | Heartbeat, thoát chủ động, timeout, host rời | Command disconnect và stopGame; kiểm tra dọn thread | Cleanup scheduler khi trận bị hủy | Dừng broadcast/listener/sender; trả UI về trạng thái đúng |
| 2.5 | Tạo lại phòng/chơi lại theo luật | Replica/runtime mới, input sequence/ACK mới theo contract | Reset bomb/item state | Cô lập matchId/epoch, bỏ packet/listener trận cũ |

Kiểm tra bắt buộc: hai phòng A/B chạy đồng thời không nhận chéo chat/snapshot; client thứ năm
bị từ chối; ready/start đồng thời không tạo hai runtime; host thoát không kẹt phòng;
start–stop/chơi lại nhiều lần không còn thread, timer hay listener của trận trước.

## 7. M3 — Ghép bom, item và kết quả trận

Thứ tự ghép là đặt bom → nổ/phá block → sát thương/chết → item → kết quả.
Mỗi bước phải chạy qua hai client trước khi sang bước kế tiếp.

### 7.1. TV3: luật gameplay và hiệu ứng

1. Chốt API BombWorld/ItemWorld với TV2; implement GameplayExtension gọi system trên game thread.
   Phân công cụ thể: TV2 làm adapter truy cập GameState; TV3 làm extension ghép system,
   cùng review để không có hai nơi lưu trạng thái bomb/player khác nhau.
2. Xử lý PlantBombCommand: còn sống, trong trận, ô hợp lệ, giới hạn bom, không đặt trùng;
   vị trí lấy từ player server, không tin tọa độ do client tự gửi.
3. Nối timer bom theo một mô hình: ưu tiên deadline được xử lý trong extension.update;
   nếu dùng BombScheduler thì callback chỉ enqueue, tuyệt đối không sửa world từ thread scheduler.
   Chọn một nguồn quyết định thời điểm nổ, không vừa tick vừa scheduler cùng gây nổ.
4. Hoàn thiện vùng nổ, tường cứng, block mềm, chain reaction, nổ đúng một lần và hoàn quota.
5. Damage, chết một lần; item spawn/pickup server-side, tránh nhặt hai lần và cập nhật chỉ số.
6. Phát event bất biến; bàn giao renderer/animation cho panel gameplay chung.
   Đồng bộ giờ hoặc truyền thời lượng phù hợp; không so deadline server trực tiếp với đồng hồ client chưa đồng bộ.

### 7.2. TV2: world, collision và vòng đời trận

1. Bổ sung adapter/state API mà TV3 cần sau khi thống nhất; không đưa mutable GameState ra socket thread.
2. Gắn extension vào runtime factory; giữ thứ tự command → movement → gameplay → snapshot rõ ràng.
3. Nối bomb blocking: chủ bom được bước ra khỏi ô vừa đặt, sau khi rời không quay lại;
   người khác bị chặn. Prediction phải biết quy tắc/thông tin cần thiết như server.
4. Đưa bomb/item/player alive/speed/map changes vào snapshot để full state có thể dựng lại world.
5. Dừng input khi chết, giữ xem người khác nếu luật cho phép; ghép renderer TV3 vào cùng màn hình.
6. Chốt với TV3 luật còn một người, cùng chết, timeout/hòa và disconnect; triển khai kết quả trên game thread.
   Match kết thúc chỉ một lần, không kết luận người thắng khi danh sách player chưa khởi tạo xong.

### 7.3. TV4: message gameplay và thứ tự dữ liệu

1. Chốt tên/mã packet: hiện tài liệu có BOMB_PLANTED/BOMB_PLACED khác nhau;
   thống nhất với event Java, không đoán tên tại mỗi module.
2. Handler PLANT_BOMB chỉ xác thực session, parse và enqueue command; xử lý duplicate request theo contract.
3. Encode event, gắn room/match/tick/eventId nếu cần; publish theo thứ tự sau khi world đã cập nhật.
4. Chọn snapshot là nguồn state chính; event phục vụ hiệu ứng/thông báo.
   Không vừa dùng event vừa snapshot cộng speed/trừ máu/nhặt item hai lần.
5. Client chuyển cập nhật presentation về EDT, loại event trùng/cũ, và phục hồi bằng full snapshot.
   Reconnect/full có thể dựng lại bomb/item đang tồn tại dù không nhận event tạo ban đầu.
6. GAME_OVER mang kết quả server; nối bảng kết quả và quay về lobby theo contract.

### 7.4. TV1: phòng và cleanup gameplay

1. Giữ chỉ thành viên đúng phòng/trận được gửi command gameplay.
2. Broadcast event/kết quả đúng phòng, không tự tính sát thương hay thắng/thua.
3. Khi trận kết thúc/hủy/phòng rỗng, gọi cleanup runtime, extension, scheduler và worker đúng một lần.
4. Khôi phục trạng thái session/room để có thể sẵn sàng chơi tiếp; không để session PLAYING trong phòng đã đóng.

Nghiệm thu M3: hai client thấy cùng bomb, vùng nổ, block bị phá, item và người thắng;
test chain reaction, cùng chết và nhặt item đồng thời; vào lại/full không dựng sai world.

## 8. M4 — Độ ổn định, reconnect nếu chọn và đo mạng

### 8.1. Ma trận kiểm thử chung

| Kịch bản | Chủ trì | Kết quả cần chứng minh |
|---|---|---|
| Frame chia từng phần, nhiều frame/đọc, length sai, payload sai | TV4 | Decoder không mất ranh giới, không cấp phát vô hạn, lỗi không làm sập server |
| Client chậm, queue đầy, spam MOVE/PLANT_BOMB | TV4 + TV1 | Có giới hạn/rate limit; không kéo chậm tất cả phòng và không mất baseline âm thầm |
| Va chạm biên/tường/block/bom, replay ACK, delta/resync | TV2 | Trạng thái server đúng, prediction phục hồi được |
| Bom trùng, chain reaction, chết/nhặt item trùng | TV3 | Mỗi tác động chỉ áp dụng một lần theo luật |
| Disconnect giữa packet/đang di chuyển/đang đặt bom | TV1 + cả nhóm | Không kẹt player, không hỏng phòng, cleanup đúng |
| Hai phòng và bốn client/phòng trong test nếu khả thi | TV1 + TV2 + TV4 | Không lẫn state, snapshot, event hay danh tính giữa phòng |
| Start/stop/chơi lại lặp lại | Người tích hợp | Không tăng thread/listener/socket không được giải phóng |
| Tắt server lúc đang chơi | TV1 + TV4 | Client báo lỗi và dừng input/timer, không treo UI |
| Chạy LAN trên ít nhất hai máy | Cả nhóm | Kết nối qua IP cấu hình và toàn bộ kịch bản demo chạy được |

Reconnect nếu triển khai: TV1 xác thực phiên/token và thay socket; TV2 dừng input cũ,
reset hoặc tiếp tục sequence/ACK theo quy tắc đã chốt, enqueue reconnect và yêu cầu full;
TV4 thay listener/network epoch, bỏ packet từ kết nối cũ, đồng bộ state rồi mới bật input.
TV3 bảo đảm full thể hiện bomb/item hiện tại. Test reconnect trong hạn, hết hạn,
kết nối cũ đóng muộn và người khác dùng token sai. Không khôi phục chỉ bằng cách đổi socket.

### 8.2. TV4 chủ trì số liệu, các thành viên cung cấp kịch bản

1. Đo riêng 1, 2, 4 client, cả đứng yên và di chuyển/đặt bom; ghi cấu hình máy, mạng, revision.
2. Đề xuất mỗi kịch bản: làm nóng 30 giây, đo 3 phút, lặp ba lần; điều chỉnh nếu thời gian môn học hạn chế,
   nhưng phải ghi đúng thời lượng thực tế và không chọn riêng lần chạy đẹp nhất.
3. Thu RTT trung bình/p95, ping timeout, packets/s, bytes/s gửi/nhận theo message,
   lỗi/ngắt kết nối, CPU/RAM; TV2 cung cấp tick time/queue rejection nếu có instrument.
4. RTT dùng thời gian đơn điệu; RTT/2 chỉ là ước lượng một chiều. Input-to-ACK là phép đo khác RTT,
   cần ghi rõ điểm bắt đầu/kết thúc. Counter ứng dụng không đại diện toàn bộ overhead TCP/IP.
5. So full/delta trên cùng workload; phân biệt benchmark codec/mô phỏng với capture mạng thực.
   Không lấy tỷ lệ tiết kiệm trong test TV2 làm tỷ lệ mặc định của mọi phiên chơi.
6. Lưu CSV gốc, cách chạy, bảng/biểu đồ; nếu dùng Wireshark, giải thích TCP stream có thể chia/gộp
   message ứng dụng và không suy số message trực tiếp từ số TCP packet.

Tiêu chí hiệu năng cần được nhóm thống nhất sau đo baseline trên máy thật. Các điều kiện bắt buộc
là không có lỗi nghiêm trọng, dữ liệu nhất quán, tài nguyên hữu hạn và demo đáp ứng được;
không tự ghi một ngưỡng ping/CPU chưa từng đo như kết quả đã đạt.

## 9. M5 — Đóng gói, báo cáo và diễn tập

| Người | Việc cần bàn giao |
|---|---|
| TV1 | Hướng dẫn server/port/LAN/firewall, sơ đồ session/room, kết quả disconnect/heartbeat và cleanup |
| TV2 | Sơ đồ command/GameLoop/snapshot, giải thích authority/prediction/reconciliation/interpolation, kết quả collision/delta |
| TV3 | Bảng luật, sơ đồ bomb lifecycle/chain reaction/item, ảnh hiệu ứng và kết quả test gameplay |
| TV4 | Protocol chính thức, byte/frame example, kết quả benchmark/CSV/capture và hướng dẫn build/run chung |
| Người tích hợp | Bản chạy có version/revision, cấu hình mẫu, changelog/known limitations, kịch bản demo hoàn chỉnh |

Các bước bàn giao:

1. Chạy `mvn test` trên môi trường có Maven và dependency; bảo đảm cả test JUnit TV1/TV3/TV4
   được chạy. Bộ check TV2 không thay thế toàn bộ test dự án.
2. Chạy `cmd /c scripts\check-tv2.cmd` để kiểm tra riêng regression TV2 và lưu kết quả cần thiết.
3. Tạo script chạy server/client và artifact phù hợp cấu hình build thực tế; README ghi đúng
   câu lệnh đã thử, không ghi lệnh jar khi chưa cấu hình executable jar.
4. Nhờ một thành viên dùng checkout sạch/máy khác làm theo README từ đầu, không dựa IDE cá nhân.
5. Diễn tập: khởi động server → hai client kết nối → tạo/join/ready → start → di chuyển
   → đặt bom/phá block/nhặt item → chết/kết quả → chơi lại → thử đóng một client.
6. Chuẩn bị video dự phòng, ảnh kiến trúc và log; video bổ sung, không thay bản chương trình chạy được.
7. Ghi hạn chế thực tế và phạm vi chưa làm; chỉ báo cáo tính năng/số liệu có bằng chứng.

## 10. Cách chia đầu việc và tránh conflict

- Mỗi ticket/PR ghi: mã mốc, người chính, module, phụ thuộc, API vào/ra, tiêu chí nghiệm thu,
  lệnh test và bằng chứng. Ví dụ: `M1-TV4-03: broadcast snapshot theo room`.
- Người sở hữu module sửa file của mình; người cần API gửi yêu cầu kèm ví dụ,
  không tự thay kiểu dữ liệu chung rồi yêu cầu mọi người sửa sau.
- Với thay đổi xuyên module, ghép theo thứ tự: contract/model → implementation tương thích
  → caller/adapter → bootstrap → test tích hợp → xóa API cũ khi mọi caller đã chuyển.
- Chỉ một đầu mối sửa đăng ký router/khởi tạo ứng dụng; không đăng ký handler cùng type hai lần.
- Có thể làm song song các module độc lập; tại mỗi mốc vẫn phải cùng kiểm tra một revision đã ghép.
- Không đưa TODO hoặc lớp rỗng vào trạng thái “đã hoàn thành”. Đánh dấu rõ: chưa làm / đang làm /
  chờ API / đã test module / đã nghiệm thu tích hợp.

Mẫu ticket:

```text
Mã: M1-TV1-02
Việc: nối ClientHandler với decoder/router
Người chính: TV1; phối hợp: TV4
Phụ thuộc: frame/decoder/router contract M0
Đầu vào: socket và session tin cậy
Đầu ra: packet được route, lỗi và disconnect được cleanup
Không thuộc phạm vi: cập nhật GameState hoặc tự parse MOVE trong ClientHandler
Nghiệm thu: TCP frame chia/gộp, packet sai, client đóng giữa frame
Bằng chứng: test + log + revision
```

## 11. Lịch đề xuất và việc nên bắt đầu ngay

Ước lượng dưới đây là số buổi làm việc của nhóm, không phải hạn nộp đã xác nhận.
Tăng thời gian dự phòng nếu API hiện có không khớp hoặc thành viên chưa chạy được build.

| Buổi | Mục tiêu | Có thể làm song song |
|---|---|---|
| 1 | M0, build mọi máy, chốt ticket và người tích hợp | TV3 rà luật/model; TV4 đối chiếu protocol |
| 2–3 | M1 hai client di chuyển TCP rồi LAN | TV1 network/session; TV2 runtime/window; TV4 route/broadcast/bootstrap |
| 4–5 | M2 lobby, start/stop, hai phòng, chơi lại | TV3 hoàn thiện extension/renderer để bàn giao M3 |
| 6–8 | M3 bom → chết/item → kết quả | TV4 codec event; TV2 adapter/collision; TV1 lifecycle |
| 9–10 | M4 test lỗi và số liệu; reconnect nếu nằm trong phạm vi | Viết báo cáo từng phần và chụp minh chứng |
| 11–12 | M5 đóng gói, kiểm tra máy sạch, diễn tập | Sửa lỗi còn lại và hoàn thiện README/báo cáo |

Việc đầu tiên của mỗi người:

- **Mai:** trao đổi Giang về điểm nối decoder/router trong ClientHandler; chuẩn bị API session/broadcast.
- **Chinh:** bàn giao ví dụ start runtime + PlayerSpawn + MovementGameWindow + resync callback cho người tích hợp.
- **Duy:** chốt world interface/model chung với Chinh và bảng luật bom/item; chuẩn bị extension.
- **Giang:** chốt schema M1 với Mai/Chinh và lắp hai bootstrap chạy được; không bắt đầu bằng tối ưu benchmark.
- **Cả nhóm:** xác nhận người tích hợp, mở ticket M0/M1 và đặt buổi nghiệm thu hai client thật.

## 12. Checklist hoàn thành đề tài

- [ ] Có protocol thống nhất với code và một đường decode/route chính thức.
- [ ] Có lệnh chạy server/client và cấu hình host/port trên máy khác.
- [ ] Có hai đến bốn client độc lập, chơi qua LAN, đúng người/đúng phòng.
- [ ] Có lobby/ready/start/chat/leave và vòng đời trận không kẹt trạng thái.
- [ ] Có movement/collision và phục hồi snapshot đúng.
- [ ] Có bom/nổ/phá block/item/chết/thắng–thua theo luật server.
- [ ] Có disconnect/heartbeat/cleanup; reconnect được kiểm thử nếu nằm trong phạm vi.
- [ ] Có test toàn dự án và bằng chứng tích hợp, không chỉ demo nội bộ.
- [ ] Có số liệu mạng gốc, điều kiện đo và nhận xét giới hạn.
- [ ] Có báo cáo, README, bản chạy, video/kịch bản và phân công đóng góp.

Tài liệu đối chiếu: [TEAM_OWNERSHIP](TEAM_OWNERSHIP.md), [ARCHITECTURE](ARCHITECTURE.md),
[protocol](protocol.md), [TV2_TEAM_ALIGNMENT](TV2_TEAM_ALIGNMENT.md),
[TV2_CONTRACT_MIGRATION](TV2_CONTRACT_MIGRATION.md).

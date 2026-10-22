# Bàn giao M0 — hợp đồng tích hợp

Cập nhật triển khai: [SERVER_TRANSPORT_STATUS.md](SERVER_TRANSPORT_STATUS.md) ghi phần
nhiệm vụ 3 đã nối và những đường gameplay/lobby/reconnect còn chưa ghép.

Ngày: 21/10/2026. Thực hiện nhiệm vụ 2 theo yêu cầu người dùng; đối chiếu báo cáo
[nhiệm vụ 1](PROJECT_STATUS_AUDIT.md). Đặc tả dùng chung là [protocol.md](protocol.md).
Đây là baseline kỹ thuật cho các lượt triển khai tiếp, không phải xác nhận toàn bộ game đã chạy.

## 1. Những quyết định kỹ thuật đã chốt

| Hạng mục | Quyết định |
|---|---|
| Transport/version | TCP frame hiện có; protocol tích hợp 2; snapshot body giữ version 2 |
| Session/player | Session UUID giữ nguyên; ID int nội bộ trận; MatchContext lưu mapping bất biến |
| Room/match | Room UUID; mỗi GameRuntime có matchId UUID mới, không dùng roomId thay match epoch |
| Match envelope | Gameplay thêm matchId đầu JSON, unwrap trước codec; không đổi body codec cũ |
| MOVE/ACK | sequence long, direction, moving; một mẫu 50 ms; ACK trong snapshot sau mô phỏng |
| Snapshot | Full/delta body v2 giữ nguyên, row-major; một consumer/runtime, không bỏ chuỗi baseline |
| Resync | REQUEST_FULL_STATE 0x0034; full bằng FULL_STATE_SYNC 0x0033 hoặc full GAME_SNAPSHOT định kỳ |
| Heartbeat | Client chủ động PING mỗi 2 s, timeout 10 s, grace 15 s; generation socket chặn callback cũ |
| READY | Setter idempotent setReady(session,boolean); toggleReady giữ cho caller cũ |
| Start | Khóa roster → tạo runtime/context → GAME_STARTED → full → bật input; rollback khi lỗi |
| Reconnect | Token + generation + barrier FIFO; giữ match/ACK, bỏ pending cũ, bắt đầu ACK+1 sau full |
| Event/world | Snapshot quyết định state; event phục vụ hiệu ứng/thông báo; không áp dụng damage/item hai lần |
| Model TV3 | Adapter rõ sang game/model hoặc migration đồng bộ M3; không đổi kiểu rải rác |
| Scheduler | Chỉ enqueue, world do GameLoop sửa; người tạo executor giữ cleanup handle |
| Tài liệu | docs/protocol.md là nguồn duy nhất; PROTOCOL.md gốc là liên kết |

Đầu mối triển khai theo lộ trình này: **TV4 giữ bootstrap/điều hướng/router/worker broadcast**;
TV1 giữ lobby/session controller và view; TV2 giữ gameplay window/world adapter;
TV3 giữ gameplay extension và hiệu ứng. Đây là phân công vận hành cho các bước Codex hỗ trợ,
không gán lại tác giả lịch sử hoặc khẳng định nhóm đã biểu quyết. Nếu người dùng đổi người tích hợp,
chỉ cập nhật sổ phân công, không cần đổi protocol.

## 2. API đã có mã và kiểm thử trong nhiệm vụ này

| API | Chủ trì | Dùng khi ghép |
|---|---|---|
| IntegrationContract | TV4 | Version và hằng số interval/timeout/maxPlayers của baseline |
| MatchContext / PlayerIdentity | TV4, phối hợp TV1/TV2 | Mapping cho GAME_STARTED; immutable, không trùng session hoặc int ID |
| MatchEnvelopeCodec | TV4 | wrap body codec cũ trước gửi; unwrap với match dự kiến trước parse/apply |
| ResyncPacketCodec | TV4 | Request có baseline tick/reason rõ; thêm match header khi lên mạng |
| PacketType.REQUEST_FULL_STATE | TV4 | Mã mới 0x0034, không đổi mã cũ |
| GameRuntime.matchId() | TV2 | Lấy UUID từ runtime, không tự tạo UUID thứ hai trong bootstrap |
| SnapshotPacketCodec.encodeFullSync/decode | TV2, phối hợp TV4 | FULL_STATE_SYNC dùng đúng body full v2; không nhận delta dưới type này |
| RoomManager.setReady | TV1 | Set ready nguyên tử trong room; gửi lại true không đảo về false; kiểm tra membership |

Không thêm JSON dependency hoặc đổi chữ ký codec cũ. MatchEnvelopeCodec kiểm tra header/type/size,
codec nghiệp vụ tiếp tục chịu trách nhiệm body. Không dùng unwrap thành công như bằng chứng MOVE hợp lệ.
Field matchId được dành riêng cho header, không lồng thêm field cùng tên trong body.

## 3. Đối chiếu các điểm mâu thuẫn từ nhiệm vụ 1

“Đã chốt” là không còn câu hỏi kỹ thuật về hướng xử lý; cột việc tiếp chỉ rõ implementation còn thiếu.

| Mã | Kết luận M0 | Việc tiếp / người làm |
|---|---|---|
| API-01 | Giữ UUID session ↔ int gameplay, có MatchContext | CONNECT_ACK/GAME_STARTED handler dùng mapping — TV1/TV4 |
| API-02 | Giữ sequence/direction/moving, không dùng inputSeq schema cũ | Handler unwrap rồi MovePacketCodec.decode — TV2/TV4 |
| API-03 | Snapshot v2 Base64, không đổi thành players/bombs/items JSON khác | Broadcast/listener dùng một codec — TV4/TV2 |
| API-04 | Runtime matchId mới mỗi trận + match envelope | Registry/generation/filter ở server/client — TV4 |
| API-05 | Giao dịch start và thứ tự FIFO đã đặc tả | Bổ sung rollback và lifecycle room/runtime — TV1/TV4 |
| API-06 | API mới dùng game/model, module cũ qua adapter/migration có test | World adapter/extension M3 — TV2/TV3 |
| API-07 | Soft/hard drop chuyển command trên FIFO và cleanup handle | Nối RoomCleanupSink với runtime — TV1/TV2 |
| API-08 | Có request 0x0034, reason/baseline, response full 0x0033 | Nối handler, rate limit, một consumer, client hai listener — TV4/TV2 |
| API-09 | bomb/item wire int; event player int; EntityData.ownerId giữ UUID | Mapping event tại adapter — TV3/TV4 |
| API-10 | docs/protocol chính; Java BombPlacedEvent → wire BOMB_PLANTED | Codec event ở M3 — TV4 |
| API-11 | RECONNECT có token/version; ACK có token unicast | Handshake/generation/timeout — TV1/TV4 |
| API-12 | READY set boolean idempotent; API setReady đã bổ sung | Handler gọi setter, không toggle — TV1/TV4 |
| API-13 | Giữ state/hostId/roomName, thêm maxPlayers; disconnect có reason | Cập nhật publisher khi ghép M2 — TV1 |
| API-14 | PacketEncoder là nguồn framing/validation; writer chỉ queue/send frame | Adapter OutboundMessage/Packet và lỗi queue — TV1/TV4 |
| API-15 | Người tạo extension/executor sở hữu cleanup handle; loop dừng trước cleanup | Bootstrap M3 giữ handle, không ngầm đổi interface — TV2/TV3/TV4 |
| API-16 (mới) | CHAT chính thức JSON type 0x0017 | ChatHandler cũ đang plaintext 0x0050: chuyển khi ghép chat M2, không thêm alias không có decoder |

## 4. Bàn giao theo bước tiếp theo

### Nhiệm vụ 3 — đường truyền server

- TV4/TV1: decode frame rồi route, bắt lỗi protocol/nghiệp vụ; không còn tính byte rác là heartbeat.
- Handshake version 2, cấp session UUID và token unicast; lưu current connection generation.
- Một writer/socket dùng giới hạn PacketEncoder; disconnect hủy reader/writer đúng generation.
- Handler chưa đăng ký phải trả UNKNOWN_MESSAGE, không giả vờ có gameplay/lobby đầy đủ.

### Nhiệm vụ 4 — movement M1

- Tạo đúng một runtime manager dùng chung; GAME_STARTED lấy runtime.matchId và mapping PlayerSpawn.
- Giữ listener trước full; wrap/unwrap match header hai chiều; input chờ full có local player.
- Một worker pollUpdate/runtime, GAME_STARTED → full theo FIFO; rate limit MOVE/resync.
- Client đăng ký cả GAME_SNAPSHOT và FULL_STATE_SYNC; parse bằng SnapshotPacketCodec sau unwrap.
- Harness cũ không wrap vẫn chạy riêng; không cho legacy packet lọt production protocol 2.

### Nhiệm vụ 5 trở đi

- M2: setReady thay toggle, room publisher/schema, chat JSON, start rollback, host/leave/play again.
- M3: world/event adapter, type mapping, scheduler cleanup; game over/HUD dựa state thực nhận.
- M4: test production timeout/race/backpressure/reconnect; test codec chưa chứng minh các đường đó.

## 5. Quyết định nghiệp vụ cần người dùng lựa chọn

Các câu hỏi này **không chặn M1 di chuyển**. Chưa ghi các phương án dưới đây thành luật đã triển khai.
Nhóm có thể trả lời theo mã B1–B6 trước khi ghép M3/reconnect.

| Mã | Cần lựa chọn | Phương án đề xuất để cân nhắc | Ảnh hưởng |
|---|---|---|---|
| B1 | Mất mạng giữ player sống và có thể bị bom giết, hay loại ngay? | Giữ ghế 15 s, dừng movement, vẫn chịu damage; hết hạn thì remove | Thắng/thua và reconnect |
| B2 | Có bật reconnect trong bản nộp đầu không? | Hoàn thành game/disconnect trước, bật reconnect sau test M4 nếu rubric cần | Phạm vi và thời gian kiểm thử |
| B3 | Luật thắng/hòa/timeout | Còn một người thắng, cùng chết hòa; timeout hòa; cân nhắc giới hạn 180 s | GameOver, timer và rankings |
| B4 | Cân bằng bom/player | Fuse 3 s, range 2 ô, quota 1, speed 150 pixel/s; explosion không xuyên block mềm | Test gameplay và prediction |
| B5 | Item drop và giới hạn nâng cấp | Drop 30%, ba loại chia đều; quota/range/speed cần chọn trần cụ thể | Random seed/test và cân bằng |
| B6 | Va chạm player-player | Cho đi xuyên nhau ở bản đầu; spawn vẫn tách nhau | Collision, prediction và spawn |

Các con số trên là gợi ý, không phải yêu cầu giảng viên hoặc số đo. Chưa có lựa chọn thì
không tự thêm luật M3 chỉ để đánh dấu checklist xong. M1 dùng cấu hình spawn/map cố định của demo,
có thể đổi cấu hình mà không đổi schema protocol.

## 6. Kết quả kiểm chứng M0 và giới hạn

IntegrationContractTest bổ sung 8 test cho golden frame/TCP read ngắn, body snapshot v2 cố định,
scope MOVE, full-sync chỉ nhận full, request resync, mapping bất biến/không trùng,
matchId khi chơi lại, READY idempotent/membership và header lỗi/frame quá lớn.
Một test có thể bao phủ nhiều assertion; không cộng số assertion thành số test.

Kết quả thực chạy: **Maven offline test BUILD SUCCESS, 23 test, 0 failure/error/skipped**,
gồm 8 test contract mới và 15 test regression. Biên dịch target Java 17;
script TV2 có 4 Check PASS, NetworkTest TCP loopback PASS. Golden snapshot v2 và MOVE cũ không đổi.
Log: target/m0-contract-tests.log, target/m0-tv2.log, target/m0-network.log và target/surefire-reports.
Output trong target là tạm thời, không commit. JVM hiện tại là Java 25; chưa chạy JVM 17 thật.
GUI/LAN, routing/header qua production bootstrap và barrier reconnect chưa nghiệm thu.

### Checklist bàn giao

- [x] Một nguồn protocol, schema và version rõ; mã cũ giữ nguyên.
- [x] Có DTO/codec/accessor/READY setter tương thích, kèm test contract.
- [x] ID/match/MOVE/ACK/full-delta/resync/heartbeat/lifecycle có quy tắc thống nhất.
- [x] Có đầu mối triển khai và bảng bàn giao theo thành viên.
- [x] Tách lựa chọn nghiệp vụ khỏi baseline kỹ thuật.
- [ ] Người dùng chọn B1–B6 trước các tính năng bị phụ thuộc.
- [ ] Nối thực tế handler/bootstrap/broadcast/UI và nghiệm thu end-to-end ở các nhiệm vụ sau.

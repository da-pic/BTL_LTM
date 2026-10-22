# Ghi chú phân công từng file và theo dõi thay đổi

Ngày lập: 21/10/2026. Căn cứ: [TEAM_OWNERSHIP.md](TEAM_OWNERSHIP.md) và
[PROJECT_COMPLETION_PLAN.md](PROJECT_COMPLETION_PLAN.md).

## Cách đọc và cập nhật

- **TV1 — Mai:** network server, session, room, chat, heartbeat và tích hợp vòng đời kết nối.
- **TV2 — Chinh:** model/core/movement/runtime, client movement, demo và test tương ứng.
- **TV3 — Duy:** bom, explosion, item, event gameplay và renderer tương ứng.
- **TV4 — Giang:** protocol, router, client network, metrics và kiểm thử mạng.
- **Chung:** file kiến trúc, cấu hình, khởi động hoặc điều phối chưa thuộc độc quyền một thành viên.
- **Chưa phân công:** chưa có đủ căn cứ xác định chủ sở hữu; không tự quy thành đóng góp của ai.

“Phụ trách” là trách nhiệm bảo trì/bàn giao theo module, **không phải xác nhận tác giả lịch sử**.
File được Codex hỗ trợ vẫn thuộc phần việc của thành viên phụ trách, không tự động thành file của TV2.
Danh sách không chứng minh chức năng đã hoàn thành hoặc đã qua kiểm thử.

Trong quá trình triển khai tiếp theo:

1. Mỗi khi tạo, đổi tên, di chuyển hoặc xóa file, cập nhật bảng kiểm kê bên dưới ngay trong lượt làm đó.
2. Với file đã có, ghi nhóm file thay đổi vào nhật ký: mốc, phụ trách, việc đã làm và kiểm chứng thực tế.
3. Giữ đường dẫn đầy đủ để phân biệt các lớp trùng tên như Direction, TileType và GameEventSink.
4. Nếu thay đổi xuyên module, ghi người chính và người phối hợp; không đổi quyền sở hữu chỉ vì sửa một lần.
5. File dự kiến chưa tạo chỉ ghi trong phần dự kiến; không đưa vào bảng file hiện có.
6. Không ghi nhận thay đổi có sẵn trong workspace là do lượt làm hiện tại thực hiện.

Không liệt kê output sinh tự động như target, cache IDE hoặc dữ liệu Git.
package-info.java thuộc cùng người phụ trách package; package chung được ghi “Chung”.

## Các trường hợp cần phối hợp hoặc còn là đề xuất

| File/phạm vi | Phụ trách hiện tại | Phối hợp và lưu ý |
|---|---|---|
| server/network/ClientHandler.java | TV1 | TV4 nối decoder/router; gameplay chuyển tới handler, không đặt luật ở socket thread |
| server/game/model và runtime | TV2 | Model/API dùng chung, TV3 review phần liên quan bom/item; TV1/TV4 dùng lifecycle |
| MovePacketCodec.java, SnapshotPacketCodec.java | TV2, theo vị trí hiện có | TV4 duyệt protocol; chuyển quyền/vị trí phải ghi rõ khi thực hiện, không duy trì hai codec |
| server/game/core/PlantBombCommand.java | TV2 | TV3 xử lý luật đặt bom qua extension; TV4 parse packet |
| server/game/event/GameEventSink.java | TV3 | Event gameplay; khác server/integration/GameEventSink.java thuộc TV1 |
| server/app, client/app | TV4 | Đầu mối tích hợp theo baseline nhiệm vụ 2; phối hợp các chủ module; không quy nhận tác giả lịch sử |
| client/lobby | TV1 | UI/controller theo baseline nhiệm vụ 2; TV4 cung cấp ClientNetwork |
| client/ui | Chung | Chưa giao toàn bộ UI cho một người; phân theo từng màn hình khi có file cụ thể |
| common/dto, server/game/api | Chung | Package chung; MatchContext cụ thể do TV4 phụ trách, phối hợp TV1/TV2 |
| PROTOCOL.md và docs/protocol.md | TV4 | docs/protocol.md là nguồn chính thức; PROTOCOL.md gốc là liên kết từ nhiệm vụ 2 |
| tools/scratch/Main.java | Chưa phân công | File thử nghiệm ngoài src; không coi là mã chạy production hoặc đóng góp đã nghiệm thu |

## File dự kiến ở những mốc tiếp theo

Chưa ấn định tên lớp khi chưa triển khai. Khi file thực sự được tạo, ghi đường dẫn cụ thể vào kiểm kê.

| Loại file sẽ bổ sung | Người phụ trách dự kiến | Mốc |
|---|---|---|
| Handler session/room/chat, callback disconnect | TV1, phối hợp TV4 về routing | M1–M2 |
| Bootstrap, worker broadcast, script chạy chung | TV4 theo baseline M0 | M1–M2 |
| Lobby view/controller | TV1 theo baseline M0 | M2 |
| World adapter truy cập GameState | TV2, phối hợp TV3 | M3 |
| GameplayExtension ghép BombSystem/ItemSystem | TV3, phối hợp TV2 | M3 |
| Codec event/handler chuyển packet thành command | TV4, phối hợp TV3 | M3 |
| Gameplay window/bảng kết quả | TV2; hiệu ứng do TV3 | M3 |
| Test mạng, benchmark, báo cáo số liệu | TV4; chủ module bổ sung test nghiệp vụ | M4–M5 |

## Nhật ký trong quá trình Codex hỗ trợ

Mỗi hàng có thể ghi nhiều file cùng công việc; dùng đường dẫn rõ ràng hoặc trỏ tới nhóm trong kiểm kê.
Các thay đổi trước ngày lập chưa được truy nguyên tác giả, vì workspace đang có nhiều thay đổi chưa commit.

| Ngày / mốc | File hoặc nhóm file | Phụ trách | Công việc trong lượt làm | Kiểm chứng |
|---|---|---|---|---|
| 21/10/2026 — Lập sổ | docs/FILE_OWNERSHIP_NOTES.md | Chung | Tạo kiểm kê theo file thực có, quy tắc cập nhật và phân biệt quyền sở hữu với tác giả | Đối chiếu danh sách file và đường dẫn |
| 21/10/2026 — Nhiệm vụ 1 | src/test/java/com/bombit/server/game/bomb/BombSystemTest.java | TV3 | Bỏ import model.TileType sai với contract BombWorld hiện tại; giữ các chỉnh sửa khác | Tái hiện lỗi javac; Maven clean test đạt, gồm 4 test bom |
| 21/10/2026 — Nhiệm vụ 1 | scripts/check-tv2.cmd | TV2 | Thêm --release 17 cho javac | 4 Check PASS, bytecode major version 61 |
| 21/10/2026 — Nhiệm vụ 1 | docs/PROJECT_STATUS_AUDIT.md; docs/PROJECT_COMPLETION_PLAN.md; docs/FILE_OWNERSHIP_NOTES.md | Chung | Tạo báo cáo hiện trạng, checklist, bảng API cần thống nhất; thêm liên kết và cập nhật kiểm kê | 15 JUnit đạt; NetworkTest và 5 demo console đạt; chưa nghiệm thu GUI/LAN |
| 21/10/2026 — Nhiệm vụ 2/M0 | src/main/java/com/bombit/common/dto/MatchContext.java; src/main/java/com/bombit/common/protocol/IntegrationContract.java, MatchEnvelopeCodec.java, ResyncPacketCodec.java, PacketType.java; src/test/java/com/bombit/common/protocol/IntegrationContractTest.java | TV4; phối hợp TV1/TV2 | Mapping bất biến, header match giữ body cũ, mã/request resync, hằng số kỹ thuật và test contract | 8 test contract + regression trong 23 JUnit đạt |
| 21/10/2026 — Nhiệm vụ 2/M0 | src/main/java/com/bombit/server/game/runtime/GameRuntime.java; src/main/java/com/bombit/server/game/movement/SnapshotPacketCodec.java | TV2; phối hợp TV4 | matchId mới mỗi runtime; codec full-sync dùng body v2, từ chối delta ở type full-sync | Test match epoch, golden snapshot và full-sync đạt |
| 21/10/2026 — Nhiệm vụ 2/M0 | src/main/java/com/bombit/server/room/RoomManager.java | TV1; phối hợp TV4 | Bổ sung setReady idempotent, giữ toggleReady tương thích | Test gửi ready lặp và membership đạt |
| 21/10/2026 — Nhiệm vụ 2/M0 | PROTOCOL.md; docs/protocol.md; docs/M0_INTEGRATION_CONTRACT.md; docs/PROJECT_COMPLETION_PLAN.md; docs/PROJECT_STATUS_AUDIT.md; docs/TV2_TEAM_ALIGNMENT.md; docs/TEAM_OWNERSHIP.md; docs/FILE_OWNERSHIP_NOTES.md | TV4 cho protocol; Chung cho kế hoạch/phân công | Một nguồn schema; bàn giao API/lifecycle, lựa chọn nghiệp vụ B1–B6 và cập nhật kiểm kê | Kiểm tra liên kết, file và kết quả test; handler/bootstrap vẫn dành cho nhiệm vụ sau |
| 21/10/2026 — Nhiệm vụ 3 | src/main/java/com/bombit/common/protocol/SessionPacketCodec.java; src/main/java/com/bombit/server/dispatch/SessionHandlers.java | TV4; phối hợp TV1 | Mới: control JSON, ACK/ERROR và handler CONNECT/PING/DISCONNECT | Test TCP handshake, payload lỗi, version, PONG và thứ tự đạt |
| 21/10/2026 — Nhiệm vụ 3 | src/main/java/com/bombit/server/network/ClientHandler.java, Server.java, SessionWriter.java, ConnectionManager.java, OutboundMessage.java | TV1; phối hợp TV4 về decoder/router/framing | Đường truyền thật, writer queue, deadline frame, ownership socket, giới hạn pool và shutdown | 10 test ServerTransportTest và 2 SessionWriterTest đạt; không ghép gameplay/lobby |
| 21/10/2026 — Nhiệm vụ 3 | src/main/java/com/bombit/server/session/Session.java, SessionManager.java; src/test/java/com/bombit/server/session/SessionManagerTest.java | TV1 | Complete handshake và sửa race scheduler đóng khi disconnect; thêm regression, giữ test cũ | Latch test tái hiện thứ tự shutdown đạt; không còn exception nền trong log cuối |
| 21/10/2026 — Nhiệm vụ 3 | src/test/java/com/bombit/server/network/ServerTransportTest.java; src/test/java/com/bombit/server/network/SessionWriterTest.java | TV1; phối hợp TV4 | Mới: test frame chia/gộp, sai, EOF dở, cleanup, concurrent writer, queue đầy/validation | Toàn bộ 36 JUnit đạt; 4 Check TV2 và NetworkTest main đạt |
| 21/10/2026 — Nhiệm vụ 3 | docs/SERVER_TRANSPORT_STATUS.md; docs/protocol.md; docs/M0_INTEGRATION_CONTRACT.md; docs/PROJECT_COMPLETION_PLAN.md; README.md; docs/FILE_OWNERSHIP_NOTES.md | Chung; TV4 cho protocol | Báo cáo/checklist, hướng dẫn chạy server transport, cập nhật kiểm kê và phạm vi chưa nghiệm thu | Kiểm tra đường dẫn/file; LAN/UI/reconnect end-to-end chưa kiểm chứng |
| 21/10/2026 — Nhiệm vụ 4/M1 | src/main/java/com/bombit/server/app/DemoGameServer.java; src/main/java/com/bombit/server/app/ServerApplication.java | TV4 tích hợp; phối hợp TV1/TV2 | Phòng cố định 2–4 người, runtime/mapping, MOVE, worker snapshot duy nhất, full/resync/rate limit và cleanup | TCP profile 2/3/4, reject người thứ năm, disconnect và epoch mới đạt |
| 21/10/2026 — Nhiệm vụ 4/M1 | src/main/java/com/bombit/client/app/DemoClientConnection.java; src/main/java/com/bombit/client/app/ClientApplication.java | TV4 tích hợp; phối hợp TV2 | Listener trước CONNECT, heartbeat/UI queue, chuyển sang game window, launcher Swing/headless | Hai client launcher ở hai JVM riêng nhận full và FINAL ACK dương |
| 21/10/2026 — Nhiệm vụ 4/M1 | src/main/java/com/bombit/common/protocol/DemoPacketCodec.java, SessionPacketCodec.java; src/main/java/com/bombit/server/dispatch/RequestRejectedException.java, SessionHandlers.java; src/main/java/com/bombit/server/network/ClientHandler.java, Server.java; src/main/java/com/bombit/client/network/ClientNetwork.java | TV4 cho codec/router/client network, TV1 cho server network | Metadata/profile M1, ACK/ERROR decode, hook sau CONNECT, lỗi nghiệp vụ, shutdown chờ lifecycle, listener-only queue | Test M1 + test transport cũ đạt; không đổi codec MOVE/body snapshot v2 |
| 21/10/2026 — Nhiệm vụ 4/M1 | src/main/java/com/bombit/client/game/movement/MovementClientController.java, MovementGameWindow.java, InputController.java | TV2 | Match-scoped receive/send, FULL_STATE_SYNC, dùng controller chung, gate input khi chờ full/resync hoặc lỗi | Test controller thật/prediction/auto-resync đạt; thao tác GUI chưa nghiệm thu |
| 21/10/2026 — Nhiệm vụ 4/M1 | src/test/java/com/bombit/integration/MovementDemoTest.java; src/test/java/com/bombit/integration/MovementDemoProcessTest.java | TV4; phối hợp TV1/TV2 | Mới: 8 test TCP/controller và 1 test ba tiến trình launcher | Toàn bộ 45 JUnit đạt, 4 Check TV2 và NetworkTest main đạt |
| 21/10/2026 — Nhiệm vụ 4/M1 | scripts/build-demo.cmd; scripts/run-server.cmd; scripts/run-client.cmd; docs/M1_MOVEMENT_DEMO.md; README.md; docs/protocol.md; docs/PROJECT_COMPLETION_PLAN.md; docs/SERVER_TRANSPORT_STATUS.md; docs/FILE_OWNERSHIP_NOTES.md | TV4 tích hợp; Chung cho báo cáo/phân công | Mới: script build/run và báo cáo M1; cập nhật hướng dẫn/profile/checklist/kiểm kê | Build script thành công, bytecode 61; JVM riêng headless đạt; Swing thủ công/LAN chưa kiểm chứng |

| 21/10/2026 — Nhiệm vụ 5/M2 | src/main/java/com/bombit/server/app/LobbyGameServer.java (mới), ServerApplication.java, DemoGameServer.java | TV4 tích hợp; phối hợp TV1/TV2 | Lobby nhiều phòng, start/end nguyên tử, runtime/snapshot riêng, resync, reset ready/chơi lại, cleanup | 7 test TCP/controller M2 đạt trong 56 JUnit; loop cũ dừng thật, worker/room/writer được dọn |
| 21/10/2026 — Nhiệm vụ 5/M2 | src/main/java/com/bombit/server/room/RoomManager.java; src/main/java/com/bombit/server/chat/ChatHandler.java, ChatMessage.java | TV1; phối hợp TV4 về schema | Capacity từng phòng, ROOM_STATE JSON, giữ DISCONNECTED, CHAT 0x0017 với danh tính server và membership khi giao, dừng/join chat worker | Hai phòng, giới hạn 2/4, host rời/chuyển quyền, chat cách ly và shutdown đạt qua TCP |
| 21/10/2026 — Nhiệm vụ 5/M2 | src/main/java/com/bombit/common/protocol/LobbyJson.java, LobbyPacketCodec.java (mới), PacketType.java, MatchEnvelopeCodec.java; src/test/java/com/bombit/common/protocol/LobbyContractTest.java (mới) | TV4 | JSON lobby có giới hạn, DTO, LIST_ROOMS/END_GAME, nhận contract M0 có roomId và dạng rút gọn; giữ mã/MOVE/snapshot cũ | 4 contract lobby đạt; JSON/Unicode/escape/field/type/UUID và match envelope được kiểm tra |
| 21/10/2026 — Nhiệm vụ 5/M2 | src/main/java/com/bombit/client/lobby/LobbyWindow.java (mới); src/main/java/com/bombit/client/app/ClientApplication.java, DemoClientConnection.java; src/main/java/com/bombit/client/game/movement/MovementGameWindow.java | TV1 lobby, TV4 bootstrap/connection, TV2 movement window | Connect/lobby/phòng/chat/host/leave/end, giữ kết nối khi replay, dọn controller/input/render, bỏ packet trận cũ | Controller thật qua TCP chơi lại 2 lượt đạt; thao tác Swing trực tiếp chưa nghiệm thu |
| 21/10/2026 — Nhiệm vụ 5/M2 | src/test/java/com/bombit/integration/LobbyLifecycleTest.java (mới) | TV4; phối hợp TV1/TV2 | 7 test TCP: hai phòng, 4+1 client, quyền/ready, start đồng thời, host/disconnect, rollback factory, replay/controller/cleanup | 56 JUnit đạt toàn dự án, build script thành công, 4 Check TV2 PASS; GUI/LAN/stress còn chờ |
| 21/10/2026 — Nhiệm vụ 5/M2 | README.md; docs/protocol.md; docs/PROJECT_COMPLETION_PLAN.md; docs/M1_MOVEMENT_DEMO.md; docs/M2_LOBBY_LIFECYCLE.md (mới); docs/FILE_OWNERSHIP_NOTES.md | Chung; TV4 cho protocol | Hướng dẫn mặc định M2/--demo M1, schema tương thích, chính sách ABORTED, checklist/test và kiểm kê | Báo cáo phân biệt kiểm thử đã chạy với GUI/LAN chưa kiểm chứng; không nhận thay đổi có sẵn là của lượt này |

Mẫu hàng bổ sung:

```text
Ngày / mốc: ...
File tạo/sửa/đổi tên/xóa: ...
Phụ trách / phối hợp: ...
Thay đổi: ...
Kiểm chứng thực chạy / phần chưa kiểm chứng: ...
```

## Kiểm kê file hiện có

Đường dẫn tính từ gốc repository. Nhãn `Module` theo phạm vi hiện có;
`Phối hợp` là file cần thành viên khác review; `Đề xuất` chưa phải phân công đã xác nhận;
`Chung` là tài nguyên chung; `Chưa chốt` là chưa đủ căn cứ phân công.

<!-- FILE_INVENTORY_START -->

| File | Phụ trách | Nhãn |
|---|---|---|
| [.gitignore](../.gitignore) | Chung | Chung |
| [PROTOCOL.md](../PROTOCOL.md) | TV4 | Module |
| [README.md](../README.md) | Chung | Chung |
| [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) | Chung | Chung |
| [docs/FILE_OWNERSHIP_NOTES.md](../docs/FILE_OWNERSHIP_NOTES.md) | Chung | Chung |
| [docs/Instruction.md](../docs/Instruction.md) | Chung | Chung |
| [docs/M0_INTEGRATION_CONTRACT.md](../docs/M0_INTEGRATION_CONTRACT.md) | Chung | Chung |
| [docs/M1_MOVEMENT_DEMO.md](../docs/M1_MOVEMENT_DEMO.md) | Chung | Chung |
| [docs/M2_LOBBY_LIFECYCLE.md](../docs/M2_LOBBY_LIFECYCLE.md) | Chung | M2 |
| [docs/PROJECT_COMPLETION_PLAN.md](../docs/PROJECT_COMPLETION_PLAN.md) | Chung | Chung |
| [docs/PROJECT_STATUS_AUDIT.md](../docs/PROJECT_STATUS_AUDIT.md) | Chung | Chung |
| [docs/SERVER_TRANSPORT_STATUS.md](../docs/SERVER_TRANSPORT_STATUS.md) | Chung | Chung |
| [docs/STATE_DELTA_COMPRESSION.md](../docs/STATE_DELTA_COMPRESSION.md) | TV2 | Module |
| [docs/TEAM_OWNERSHIP.md](../docs/TEAM_OWNERSHIP.md) | Chung | Chung |
| [docs/TV2_CONTRACT_MIGRATION.md](../docs/TV2_CONTRACT_MIGRATION.md) | TV2 | Module |
| [docs/TV2_TEAM_ALIGNMENT.md](../docs/TV2_TEAM_ALIGNMENT.md) | TV2 | Module |
| [docs/Topics.md](../docs/Topics.md) | Chung | Chung |
| [docs/phancong.md](../docs/phancong.md) | Chung | Chung |
| [docs/protocol.md](../docs/protocol.md) | TV4 | Module |
| [docs/thanhvien1.md](../docs/thanhvien1.md) | TV1 | Module |
| [docs/thanhvien2.md](../docs/thanhvien2.md) | TV2 | Module |
| [docs/thanhvien3.md](../docs/thanhvien3.md) | TV3 | Module |
| [docs/thanhvien4.md](../docs/thanhvien4.md) | TV4 | Module |
| [phancong/nhiemvu.md](../phancong/nhiemvu.md) | Chung | Chung |
| [pom.xml](../pom.xml) | Chung | Chung |
| [scripts/build-demo.cmd](../scripts/build-demo.cmd) | TV4 | Phối hợp |
| [scripts/check-tv2.cmd](../scripts/check-tv2.cmd) | TV2 | Module |
| [scripts/run-client.cmd](../scripts/run-client.cmd) | TV4 | Phối hợp |
| [scripts/run-server.cmd](../scripts/run-server.cmd) | TV4 | Phối hợp |
| [src/main/java/com/bombit/client/app/ClientApplication.java](../src/main/java/com/bombit/client/app/ClientApplication.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/client/app/DemoClientConnection.java](../src/main/java/com/bombit/client/app/DemoClientConnection.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/client/app/package-info.java](../src/main/java/com/bombit/client/app/package-info.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/client/game/bomb/BombItemEffectsDemo.java](../src/main/java/com/bombit/client/game/bomb/BombItemEffectsDemo.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/BombItemEffectsPanel.java](../src/main/java/com/bombit/client/game/bomb/BombItemEffectsPanel.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/BombPresentationState.java](../src/main/java/com/bombit/client/game/bomb/BombPresentationState.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/BombRenderer.java](../src/main/java/com/bombit/client/game/bomb/BombRenderer.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/BombView.java](../src/main/java/com/bombit/client/game/bomb/BombView.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/ExplosionRenderer.java](../src/main/java/com/bombit/client/game/bomb/ExplosionRenderer.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/ExplosionView.java](../src/main/java/com/bombit/client/game/bomb/ExplosionView.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/bomb/package-info.java](../src/main/java/com/bombit/client/game/bomb/package-info.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/item/ItemPresentationState.java](../src/main/java/com/bombit/client/game/item/ItemPresentationState.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/item/ItemRenderer.java](../src/main/java/com/bombit/client/game/item/ItemRenderer.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/item/ItemView.java](../src/main/java/com/bombit/client/game/item/ItemView.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/item/package-info.java](../src/main/java/com/bombit/client/game/item/package-info.java) | TV3 | Module |
| [src/main/java/com/bombit/client/game/movement/ClientWorldState.java](../src/main/java/com/bombit/client/game/movement/ClientWorldState.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/InputController.java](../src/main/java/com/bombit/client/game/movement/InputController.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/InterpolationController.java](../src/main/java/com/bombit/client/game/movement/InterpolationController.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/MovementClientController.java](../src/main/java/com/bombit/client/game/movement/MovementClientController.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/MovementGameWindow.java](../src/main/java/com/bombit/client/game/movement/MovementGameWindow.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/PredictionController.java](../src/main/java/com/bombit/client/game/movement/PredictionController.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/ReconciliationController.java](../src/main/java/com/bombit/client/game/movement/ReconciliationController.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/movement/package-info.java](../src/main/java/com/bombit/client/game/movement/package-info.java) | TV2 | Module |
| [src/main/java/com/bombit/client/game/package-info.java](../src/main/java/com/bombit/client/game/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/client/lobby/LobbyWindow.java](../src/main/java/com/bombit/client/lobby/LobbyWindow.java) | TV1 | M2 |
| [src/main/java/com/bombit/client/lobby/package-info.java](../src/main/java/com/bombit/client/lobby/package-info.java) | TV1 | Phối hợp |
| [src/main/java/com/bombit/client/network/ClientNetwork.java](../src/main/java/com/bombit/client/network/ClientNetwork.java) | TV4 | Module |
| [src/main/java/com/bombit/client/network/package-info.java](../src/main/java/com/bombit/client/network/package-info.java) | TV4 | Module |
| [src/main/java/com/bombit/client/package-info.java](../src/main/java/com/bombit/client/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/client/ui/package-info.java](../src/main/java/com/bombit/client/ui/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/common/dto/MatchContext.java](../src/main/java/com/bombit/common/dto/MatchContext.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/common/dto/package-info.java](../src/main/java/com/bombit/common/dto/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/common/package-info.java](../src/main/java/com/bombit/common/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/common/protocol/DemoPacketCodec.java](../src/main/java/com/bombit/common/protocol/DemoPacketCodec.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/common/protocol/IntegrationContract.java](../src/main/java/com/bombit/common/protocol/IntegrationContract.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/LobbyJson.java](../src/main/java/com/bombit/common/protocol/LobbyJson.java) | TV4 | M2 |
| [src/main/java/com/bombit/common/protocol/LobbyPacketCodec.java](../src/main/java/com/bombit/common/protocol/LobbyPacketCodec.java) | TV4 | M2 |
| [src/main/java/com/bombit/common/protocol/MatchEnvelopeCodec.java](../src/main/java/com/bombit/common/protocol/MatchEnvelopeCodec.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/common/protocol/Packet.java](../src/main/java/com/bombit/common/protocol/Packet.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/PacketDecoder.java](../src/main/java/com/bombit/common/protocol/PacketDecoder.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/PacketEncoder.java](../src/main/java/com/bombit/common/protocol/PacketEncoder.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/PacketType.java](../src/main/java/com/bombit/common/protocol/PacketType.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/ProtocolException.java](../src/main/java/com/bombit/common/protocol/ProtocolException.java) | TV4 | Module |
| [src/main/java/com/bombit/common/protocol/ResyncPacketCodec.java](../src/main/java/com/bombit/common/protocol/ResyncPacketCodec.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/common/protocol/SessionPacketCodec.java](../src/main/java/com/bombit/common/protocol/SessionPacketCodec.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/common/protocol/package-info.java](../src/main/java/com/bombit/common/protocol/package-info.java) | TV4 | Module |
| [src/main/java/com/bombit/metrics/LatencyMonitor.java](../src/main/java/com/bombit/metrics/LatencyMonitor.java) | TV4 | Module |
| [src/main/java/com/bombit/metrics/ThroughputMonitor.java](../src/main/java/com/bombit/metrics/ThroughputMonitor.java) | TV4 | Module |
| [src/main/java/com/bombit/metrics/package-info.java](../src/main/java/com/bombit/metrics/package-info.java) | TV4 | Module |
| [src/main/java/com/bombit/server/app/DemoGameServer.java](../src/main/java/com/bombit/server/app/DemoGameServer.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/server/app/LobbyGameServer.java](../src/main/java/com/bombit/server/app/LobbyGameServer.java) | TV4 | M2 |
| [src/main/java/com/bombit/server/app/ServerApplication.java](../src/main/java/com/bombit/server/app/ServerApplication.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/server/app/package-info.java](../src/main/java/com/bombit/server/app/package-info.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/server/chat/ChatHandler.java](../src/main/java/com/bombit/server/chat/ChatHandler.java) | TV1 | Module |
| [src/main/java/com/bombit/server/chat/ChatMessage.java](../src/main/java/com/bombit/server/chat/ChatMessage.java) | TV1 | Module |
| [src/main/java/com/bombit/server/dispatch/MessageHandler.java](../src/main/java/com/bombit/server/dispatch/MessageHandler.java) | TV4 | Module |
| [src/main/java/com/bombit/server/dispatch/MessageRouter.java](../src/main/java/com/bombit/server/dispatch/MessageRouter.java) | TV4 | Module |
| [src/main/java/com/bombit/server/dispatch/RequestRejectedException.java](../src/main/java/com/bombit/server/dispatch/RequestRejectedException.java) | TV4 | Module |
| [src/main/java/com/bombit/server/dispatch/SessionHandlers.java](../src/main/java/com/bombit/server/dispatch/SessionHandlers.java) | TV4 | Phối hợp |
| [src/main/java/com/bombit/server/dispatch/package-info.java](../src/main/java/com/bombit/server/dispatch/package-info.java) | TV4 | Module |
| [src/main/java/com/bombit/server/game/api/package-info.java](../src/main/java/com/bombit/server/game/api/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/server/game/bomb/Bomb.java](../src/main/java/com/bombit/server/game/bomb/Bomb.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombError.java](../src/main/java/com/bombit/server/game/bomb/BombError.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombPlacementResult.java](../src/main/java/com/bombit/server/game/bomb/BombPlacementResult.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombScheduler.java](../src/main/java/com/bombit/server/game/bomb/BombScheduler.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombState.java](../src/main/java/com/bombit/server/game/bomb/BombState.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombSystem.java](../src/main/java/com/bombit/server/game/bomb/BombSystem.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/BombWorld.java](../src/main/java/com/bombit/server/game/bomb/BombWorld.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/Direction.java](../src/main/java/com/bombit/server/game/bomb/Direction.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/ExplosionResult.java](../src/main/java/com/bombit/server/game/bomb/ExplosionResult.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/TilePosition.java](../src/main/java/com/bombit/server/game/bomb/TilePosition.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/TileType.java](../src/main/java/com/bombit/server/game/bomb/TileType.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/bomb/package-info.java](../src/main/java/com/bombit/server/game/bomb/package-info.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/core/GameCommand.java](../src/main/java/com/bombit/server/game/core/GameCommand.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/GameLoop.java](../src/main/java/com/bombit/server/game/core/GameLoop.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/GameMap.java](../src/main/java/com/bombit/server/game/core/GameMap.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/GameState.java](../src/main/java/com/bombit/server/game/core/GameState.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/MoveCommand.java](../src/main/java/com/bombit/server/game/core/MoveCommand.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/PlantBombCommand.java](../src/main/java/com/bombit/server/game/core/PlantBombCommand.java) | TV2 | Phối hợp |
| [src/main/java/com/bombit/server/game/core/Player.java](../src/main/java/com/bombit/server/game/core/Player.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/PlayerDisconnectedCommand.java](../src/main/java/com/bombit/server/game/core/PlayerDisconnectedCommand.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/PlayerReconnectedCommand.java](../src/main/java/com/bombit/server/game/core/PlayerReconnectedCommand.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/StopMovingCommand.java](../src/main/java/com/bombit/server/game/core/StopMovingCommand.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/core/package-info.java](../src/main/java/com/bombit/server/game/core/package-info.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/event/BlockDestroyedEvent.java](../src/main/java/com/bombit/server/game/event/BlockDestroyedEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/BombExplodedEvent.java](../src/main/java/com/bombit/server/game/event/BombExplodedEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/BombPlacedEvent.java](../src/main/java/com/bombit/server/game/event/BombPlacedEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/GameEvent.java](../src/main/java/com/bombit/server/game/event/GameEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/GameEventSink.java](../src/main/java/com/bombit/server/game/event/GameEventSink.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/ItemPickedUpEvent.java](../src/main/java/com/bombit/server/game/event/ItemPickedUpEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/ItemSpawnedEvent.java](../src/main/java/com/bombit/server/game/event/ItemSpawnedEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/PlayerDiedEvent.java](../src/main/java/com/bombit/server/game/event/PlayerDiedEvent.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/event/package-info.java](../src/main/java/com/bombit/server/game/event/package-info.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/Item.java](../src/main/java/com/bombit/server/game/item/Item.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/ItemSpawnPolicy.java](../src/main/java/com/bombit/server/game/item/ItemSpawnPolicy.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/ItemState.java](../src/main/java/com/bombit/server/game/item/ItemState.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/ItemSystem.java](../src/main/java/com/bombit/server/game/item/ItemSystem.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/ItemType.java](../src/main/java/com/bombit/server/game/item/ItemType.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/ItemWorld.java](../src/main/java/com/bombit/server/game/item/ItemWorld.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/RandomItemSpawnPolicy.java](../src/main/java/com/bombit/server/game/item/RandomItemSpawnPolicy.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/item/package-info.java](../src/main/java/com/bombit/server/game/item/package-info.java) | TV3 | Module |
| [src/main/java/com/bombit/server/game/model/Direction.java](../src/main/java/com/bombit/server/game/model/Direction.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/model/TilePosition.java](../src/main/java/com/bombit/server/game/model/TilePosition.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/model/TileType.java](../src/main/java/com/bombit/server/game/model/TileType.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/CollisionSystem.java](../src/main/java/com/bombit/server/game/movement/CollisionSystem.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/EntityData.java](../src/main/java/com/bombit/server/game/movement/EntityData.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/GameStateUpdate.java](../src/main/java/com/bombit/server/game/movement/GameStateUpdate.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/MovePacketCodec.java](../src/main/java/com/bombit/server/game/movement/MovePacketCodec.java) | TV2 | Phối hợp |
| [src/main/java/com/bombit/server/game/movement/MovementHandler.java](../src/main/java/com/bombit/server/game/movement/MovementHandler.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/MovementSystem.java](../src/main/java/com/bombit/server/game/movement/MovementSystem.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/SnapshotPacketCodec.java](../src/main/java/com/bombit/server/game/movement/SnapshotPacketCodec.java) | TV2 | Phối hợp |
| [src/main/java/com/bombit/server/game/movement/StateDeltaBuilder.java](../src/main/java/com/bombit/server/game/movement/StateDeltaBuilder.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/movement/package-info.java](../src/main/java/com/bombit/server/game/movement/package-info.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/package-info.java](../src/main/java/com/bombit/server/game/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/server/game/runtime/GameRuntime.java](../src/main/java/com/bombit/server/game/runtime/GameRuntime.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/runtime/GameRuntimeManager.java](../src/main/java/com/bombit/server/game/runtime/GameRuntimeManager.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/runtime/GameplayExtension.java](../src/main/java/com/bombit/server/game/runtime/GameplayExtension.java) | TV2 | Module |
| [src/main/java/com/bombit/server/game/runtime/PlayerSpawn.java](../src/main/java/com/bombit/server/game/runtime/PlayerSpawn.java) | TV2 | Module |
| [src/main/java/com/bombit/server/heartbeat/HeartbeatManager.java](../src/main/java/com/bombit/server/heartbeat/HeartbeatManager.java) | TV1 | Module |
| [src/main/java/com/bombit/server/integration/GameEventSink.java](../src/main/java/com/bombit/server/integration/GameEventSink.java) | TV1 | Module |
| [src/main/java/com/bombit/server/integration/NoOpGameEventSink.java](../src/main/java/com/bombit/server/integration/NoOpGameEventSink.java) | TV1 | Module |
| [src/main/java/com/bombit/server/integration/RoomCleanupSink.java](../src/main/java/com/bombit/server/integration/RoomCleanupSink.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/BroadcastSender.java](../src/main/java/com/bombit/server/network/BroadcastSender.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/ClientHandler.java](../src/main/java/com/bombit/server/network/ClientHandler.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/ConnectionManager.java](../src/main/java/com/bombit/server/network/ConnectionManager.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/OutboundMessage.java](../src/main/java/com/bombit/server/network/OutboundMessage.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/OutboundSender.java](../src/main/java/com/bombit/server/network/OutboundSender.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/Server.java](../src/main/java/com/bombit/server/network/Server.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/SessionWriter.java](../src/main/java/com/bombit/server/network/SessionWriter.java) | TV1 | Module |
| [src/main/java/com/bombit/server/network/package-info.java](../src/main/java/com/bombit/server/network/package-info.java) | TV1 | Module |
| [src/main/java/com/bombit/server/package-info.java](../src/main/java/com/bombit/server/package-info.java) | Chung | Chung |
| [src/main/java/com/bombit/server/room/Room.java](../src/main/java/com/bombit/server/room/Room.java) | TV1 | Module |
| [src/main/java/com/bombit/server/room/RoomBroadcaster.java](../src/main/java/com/bombit/server/room/RoomBroadcaster.java) | TV1 | Module |
| [src/main/java/com/bombit/server/room/RoomManager.java](../src/main/java/com/bombit/server/room/RoomManager.java) | TV1 | Module |
| [src/main/java/com/bombit/server/room/package-info.java](../src/main/java/com/bombit/server/room/package-info.java) | TV1 | Module |
| [src/main/java/com/bombit/server/session/Session.java](../src/main/java/com/bombit/server/session/Session.java) | TV1 | Module |
| [src/main/java/com/bombit/server/session/SessionManager.java](../src/main/java/com/bombit/server/session/SessionManager.java) | TV1 | Module |
| [src/main/java/com/bombit/server/session/SessionState.java](../src/main/java/com/bombit/server/session/SessionState.java) | TV1 | Module |
| [src/main/java/com/bombit/server/session/package-info.java](../src/main/java/com/bombit/server/session/package-info.java) | TV1 | Module |
| [src/test/java/com/bombit/client/debug/GameDebugWindow.java](../src/test/java/com/bombit/client/debug/GameDebugWindow.java) | TV2 | Module |
| [src/test/java/com/bombit/client/game/bomb/BombPresentationStateTest.java](../src/test/java/com/bombit/client/game/bomb/BombPresentationStateTest.java) | TV3 | Module |
| [src/test/java/com/bombit/client/game/bomb/Java2DRendererTest.java](../src/test/java/com/bombit/client/game/bomb/Java2DRendererTest.java) | TV3 | Module |
| [src/test/java/com/bombit/common/protocol/IntegrationContractTest.java](../src/test/java/com/bombit/common/protocol/IntegrationContractTest.java) | TV4 | Phối hợp |
| [src/test/java/com/bombit/common/protocol/LobbyContractTest.java](../src/test/java/com/bombit/common/protocol/LobbyContractTest.java) | TV4 | M2 |
| [src/test/java/com/bombit/integration/LobbyLifecycleTest.java](../src/test/java/com/bombit/integration/LobbyLifecycleTest.java) | TV4 | M2 |
| [src/test/java/com/bombit/integration/MovementDemoProcessTest.java](../src/test/java/com/bombit/integration/MovementDemoProcessTest.java) | TV4 | Phối hợp |
| [src/test/java/com/bombit/integration/MovementDemoTest.java](../src/test/java/com/bombit/integration/MovementDemoTest.java) | TV4 | Phối hợp |
| [src/test/java/com/bombit/integration/NetworkTest.java](../src/test/java/com/bombit/integration/NetworkTest.java) | TV4 | Module |
| [src/test/java/com/bombit/integration/TestScenario.java](../src/test/java/com/bombit/integration/TestScenario.java) | TV4 | Module |
| [src/test/java/com/bombit/performance/Benchmark.java](../src/test/java/com/bombit/performance/Benchmark.java) | TV4 | Module |
| [src/test/java/com/bombit/server/game/bomb/BombSchedulerTest.java](../src/test/java/com/bombit/server/game/bomb/BombSchedulerTest.java) | TV3 | Module |
| [src/test/java/com/bombit/server/game/bomb/BombSystemTest.java](../src/test/java/com/bombit/server/game/bomb/BombSystemTest.java) | TV3 | Module |
| [src/test/java/com/bombit/server/game/core/GameCommandQueueDemo.java](../src/test/java/com/bombit/server/game/core/GameCommandQueueDemo.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/core/GameLoopDemo.java](../src/test/java/com/bombit/server/game/core/GameLoopDemo.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/core/GameStateDemo.java](../src/test/java/com/bombit/server/game/core/GameStateDemo.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/item/ItemSystemTest.java](../src/test/java/com/bombit/server/game/item/ItemSystemTest.java) | TV3 | Module |
| [src/test/java/com/bombit/server/game/movement/ApiMigrationCheck.java](../src/test/java/com/bombit/server/game/movement/ApiMigrationCheck.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/DeltaCompressionCheck.java](../src/test/java/com/bombit/server/game/movement/DeltaCompressionCheck.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/DeltaCompressionTest.java](../src/test/java/com/bombit/server/game/movement/DeltaCompressionTest.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/MovementFeatureCheck.java](../src/test/java/com/bombit/server/game/movement/MovementFeatureCheck.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/MovementFeatureTest.java](../src/test/java/com/bombit/server/game/movement/MovementFeatureTest.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/MovementSystemDemo.java](../src/test/java/com/bombit/server/game/movement/MovementSystemDemo.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/movement/StateDeltaBuilderDemo.java](../src/test/java/com/bombit/server/game/movement/StateDeltaBuilderDemo.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/runtime/RuntimeContractCheck.java](../src/test/java/com/bombit/server/game/runtime/RuntimeContractCheck.java) | TV2 | Module |
| [src/test/java/com/bombit/server/game/runtime/RuntimeContractTest.java](../src/test/java/com/bombit/server/game/runtime/RuntimeContractTest.java) | TV2 | Module |
| [src/test/java/com/bombit/server/network/ServerTransportTest.java](../src/test/java/com/bombit/server/network/ServerTransportTest.java) | TV1 | Phối hợp |
| [src/test/java/com/bombit/server/network/SessionWriterTest.java](../src/test/java/com/bombit/server/network/SessionWriterTest.java) | TV1 | Module |
| [src/test/java/com/bombit/server/room/RoomManagerTest.java](../src/test/java/com/bombit/server/room/RoomManagerTest.java) | TV1 | Module |
| [src/test/java/com/bombit/server/session/SessionManagerTest.java](../src/test/java/com/bombit/server/session/SessionManagerTest.java) | TV1 | Module |
| [tools/manual-client/TestClient.java](../tools/manual-client/TestClient.java) | TV4 | Module |
| [tools/scratch/Main.java](../tools/scratch/Main.java) | Chưa phân công | Chưa chốt |

<!-- FILE_INVENTORY_END -->

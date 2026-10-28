# M2 — Lobby và vòng đời trận

Ghi chú hiện trạng: nhiệm vụ 7 đã bổ sung bom vào lobby mặc định, Space và renderer TV3.
Báo cáo bên dưới giữ kết quả lịch sử M2; xem [TASK7_CLIENT_BOMBS.md](TASK7_CLIENT_BOMBS.md).

Ngày kiểm tra: 21/10/2026. Đã triển khai luồng kết nối, phòng và trận chỉ di chuyển.
Kiểm thử TCP/controller đạt; thao tác Swing và LAN còn chờ nghiệm thu trực tiếp.
Đây là kết quả nhiệm vụ 5, không phải tuyên bố hoàn thành toàn bộ đề tài.

## Chạy bản M2

Cần JDK 25+ trong PATH. Chạy tại thư mục chứa `pom.xml`:

```powershell
cmd /c scripts\build-demo.cmd
cmd /c scripts\run-server.cmd 8888
```

Mở từng terminal client riêng:

```powershell
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Chinh
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Mai
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Duy
cmd /c scripts\run-client.cmd 127.0.0.1 8888 Giang
```

1. Mỗi cửa sổ bấm **Connect**. Host/port/tên có thể sửa trước khi kết nối.
2. Chinh tạo phòng A, chọn sức chứa 2–4. Mai chọn phòng A và **Join**.
3. Khách bấm **Ready**; host bấm **Start**. Tối thiểu hai người; mọi khách phải ready.
4. Cửa sổ movement mở riêng, dùng phím mũi tên. Cửa sổ lobby vẫn mở để chat hoặc kết thúc lượt.
5. **End round** dành cho host: đóng trận, giữ phòng và kết nối, reset ready. Ready/Start để chơi lại.
6. **Leave room** đưa người bấm về danh sách phòng. Người rời khi đang chơi làm hủy lượt cho cả phòng.
7. Đóng cửa sổ movement: host kết thúc lượt; khách rời phòng. Đóng cửa sổ lobby đóng kết nối.
8. Mất kết nối: UI báo lỗi và cho Connect lại bằng phiên mới. Chưa nối RECONNECT/token vào ứng dụng.

Hai người Duy/Giang có thể tạo phòng B và chơi đồng thời. Phòng trống tự xóa.
Host rời: người còn lại có thứ tự vào sớm nhất trở thành host; không chọn host ngẫu nhiên.
Mất mạng cũng bỏ ghế ngay trong M2; session có thể được transport giữ tối đa 15 giây trước hard drop,
nhưng không giữ room/runtime hoặc chấp nhận bù người vào trận cũ.

M1 vẫn chạy bằng `run-server.cmd 8888 2` và client thêm `--demo` sau tên;
`--headless 4` vẫn là smoke client của M1. Không trộn hai profile.

## Hành vi đã kiểm thử

- [x] CREATE/JOIN/LEAVE, danh sách phòng tự cập nhật, sức chứa 2–4; phòng bốn người từ chối người thứ năm.
- [x] READY là set idempotent. Khách không start/end thay host; chưa đủ ready thì không start.
- [x] Request có roomId sai bị từ chối; MOVE/END trận trước bị từ chối theo matchId.
- [x] Hai phòng chạy runtime đồng thời, snapshot và chat chỉ tới đúng phòng.
- [x] Movement/ACK và resync đi qua TCP trong profile lobby, dùng module M1.
- [x] Hai request Start đồng thời chỉ tạo một match và một snapshot worker.
- [x] Host rời khi chờ hoặc đang chơi, tự chuyển quyền host; disconnect hủy lượt và bỏ ghế.
- [x] Kết thúc lượt → reset ready → start lại: matchId mới, controller mới, input/ACK từ 1.
- [x] Runtime factory lỗi: không để phòng PLAYING giả; có thể start lại sau khi factory hoạt động.
- [x] Runtime cũ dừng thread, bị xóa khỏi manager; snapshot worker về 0 sau kết thúc.
- [x] Đóng fixture server: không còn trận, worker, phòng hoặc connection writer.
- [x] Codec/frame round trip tên Unicode/escape; từ chối JSON trùng field, sai kiểu, sai giới hạn.
- [ ] Thao tác Swing trực tiếp: focus phím, đóng/mở cửa sổ nhiều lần, chat trong khi chơi, Connect lại khi sai tên/server tắt.
- [ ] Chạy 2–4 client trên nhiều máy LAN, firewall, mất mạng thực tế và kiểm tra shutdown dài hạn.
- [ ] Stress nhiều phòng, slow consumer/chat flood, đo latency/bandwidth: thuộc M4.

Không đánh dấu hoàn tất nghiệm thu GUI/LAN chỉ vì test TCP đạt.

## Kiểm thử thực chạy

Lệnh chuẩn: `mvn test`; có thể chạy riêng `mvn "-Dtest=LobbyContractTest,LobbyLifecycleTest" test`.
Môi trường thực tế: JDK 25.0.1, compile `--release 17`, Maven 3.9.12 offline với dependency đã cache.
Kết quả cuối: **56 JUnit đạt**, 0 fail/error/skip, gồm 45 test hồi quy, 4 LobbyContractTest,
7 LobbyLifecycleTest. Log thực chạy: `target/m2-tests.log`; báo cáo XML tại `target/surefire-reports/`.
Test hồi quy bao gồm smoke M1 ở ba JVM riêng; không coi đó là nghiệm thu giao diện M2.

`cmd /c scripts\build-demo.cmd`: thành công (log `target/m2-build.log`).
`cmd /c scripts\check-tv2.cmd`: 4 Check PASS (log `target/m2-tv2.log`), gồm API/wire,
movement/prediction/TCP, runtime/thread cleanup và delta compression. Log là output cục bộ,
không đưa `target/` vào kiểm kê hoặc commit.

`LobbyLifecycleTest` dùng socket TCP thật tới `LobbyGameServer`, gồm hai phòng/tối đa năm client,
và hai `GameClientConnection` thật với UI executor để kiểm tra controller/heartbeat/đổi trận.
Kiểm thử này không bấm nút Swing và không thay thế kiểm tra nhiều máy.

## File được tạo/sửa trong nhiệm vụ 5

| File | Phụ trách | Thay đổi |
|---|---|---|
| src/main/java/com/bombit/server/app/LobbyGameServer.java (mới) | TV4, phối hợp TV1/TV2 | Router lobby, chuyển trạng thái nguyên tử, runtime riêng từng phòng, broadcast/resync và cleanup |
| src/main/java/com/bombit/server/app/ServerApplication.java | TV4 | Mặc định M2; đối số số người vẫn chọn M1 |
| src/main/java/com/bombit/server/app/DemoGameServer.java | TV4 | Cho M2 dùng lại factory map movement |
| src/main/java/com/bombit/server/room/RoomManager.java | TV1 | Capacity từng phòng, ROOM_STATE qua codec chung, giữ DISCONNECTED khi leave |
| src/main/java/com/bombit/server/chat/ChatMessage.java, ChatHandler.java | TV1 | JSON CHAT 0x0017, tác giả từ session, kiểm tra membership lúc giao, join worker khi close |
| src/main/java/com/bombit/common/protocol/LobbyJson.java, LobbyPacketCodec.java (mới) | TV4 | DTO/JSON lobby có giới hạn và kiểm tra field |
| src/main/java/com/bombit/common/protocol/PacketType.java, MatchEnvelopeCodec.java | TV4 | LIST_ROOMS và END_GAME, không đổi mã cũ |
| src/main/java/com/bombit/client/lobby/LobbyWindow.java (mới) | TV1 | Connect, danh sách phòng, ready/host/chat/end/leave, mở lại movement window |
| src/main/java/com/bombit/client/app/GameClientConnection.java | TV4 | Dùng chung M1/M2, lỗi yêu cầu có thể thử lại, GAME_OVER, bỏ snapshot cũ, guard resync cũ |
| src/main/java/com/bombit/client/app/ClientApplication.java | TV4 | Mặc định lobby UI, giữ --demo/--headless M1 |
| src/main/java/com/bombit/client/game/movement/MovementGameWindow.java | TV2 | Dừng input/render và dispose mà không đóng kết nối lobby |
| src/test/java/com/bombit/integration/LobbyLifecycleTest.java (mới) | TV4, phối hợp TV1/TV2 | 7 test TCP/controller, kiểm tra cleanup thực tế |
| src/test/java/com/bombit/common/protocol/LobbyContractTest.java (mới) | TV4 | 4 test contract lobby |
| README.md; docs/protocol.md; docs/PROJECT_COMPLETION_PLAN.md; docs/M1_MOVEMENT_DEMO.md; docs/M2_LOBBY_LIFECYCLE.md (mới); docs/FILE_OWNERSHIP_NOTES.md | Chung/TV4 theo kiểm kê | Hướng dẫn, schema, checklist, bằng chứng và phân công file |

Những thay đổi khác có sẵn trong workspace không được tính là thay đổi của nhiệm vụ 5.

## Chính sách M2 và bước tiếp theo

Trong bản chỉ di chuyển, kết thúc hoặc mất một thành viên là **ABORTED**, không tính thắng/thua.
Server dừng/join loop, ngừng worker cũ, gửi GAME_OVER có matchId, tick cuối, winnerPlayerId=null,
rankings=[], rồi WAITING; mọi người còn trong phòng trở về IN_ROOM. Không gửi FINISHED giả cho một lượt hủy.
Lobby còn lại có thể leave về danh sách hoặc ready để chơi tiếp. Lỗi khởi tạo không tiêu thụ ready.

Chế độ STANDARD hiện là map movement chung, chưa có lựa chọn luật bom/item. B1–B6 trong M0 vẫn cần
người dùng lựa chọn trước khi ghép gameplay M3. Giới hạn ban đầu: 32 phòng; các giới hạn connection/queue
của transport vẫn áp dụng. Chưa có bảo đảm hiệu năng ở mức tải này.

Bước tiếp theo: nghiệm thu checklist Swing/LAN ở trên, sau đó M3 ghép bom → nổ/phá block → item/chết → kết quả
qua gameplay extension và protocol hiện hành, bổ sung cleanup tài nguyên TV3 và test end-to-end.

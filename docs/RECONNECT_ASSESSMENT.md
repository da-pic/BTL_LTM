# Khôi phục kết nối và đánh giá lịch sử

## Trạng thái hiện tại — 28/10/2026

Lobby bật `seamlessRecovery` và client bật `autoReconnect` mặc định.
Server giữ chỗ 30 giây sau khi phát hiện mất kết nối; nhân vật dừng di chuyển,
vẫn chịu sát thương, bom và đồng hồ trận tiếp tục chạy. Hết hạn chỉ loại người đó;
trận có thể kết thúc bình thường khi chỉ còn một người sống.

Client còn mở sẽ tự thử TCP/RECONNECT bằng playerId và token trong bộ nhớ.
Input tạm khóa đến khi nhận full state; giữ bộ đếm MOVE và PLANT_BOMB khi khôi phục,
bỏ prediction chưa xác nhận. Server thay writer/socket, bỏ callback từ socket cũ,
và kiểm tra generation của timer hết hạn. Nếu trận kết thúc trong thời gian chờ,
client nhận GAME_OVER và quay về phòng thay vì mở lại trận.

Đóng ứng dụng/End task không thể tự khởi động lại client; token không được lưu ra đĩa.
Chủ động Leave room vẫn hủy lượt. Profile M1 `--demo` không hỗ trợ resume.
Hướng dẫn demo LAN/loopback: [DEMO_SCRIPT.md](DEMO_SCRIPT.md).

## Đánh giá lịch sử trước khi triển khai

Ngày rà soát: 22/10/2026. **Chưa triển khai reconnect trong ứng dụng.**
Server transport/lobby không đăng ký PacketType.RECONNECT; test nhiệm vụ 9 xác
nhận route vẫn không có. Connect lại từ UI hiện là một phiên mới, không resume trận.

## Phần có sẵn và giới hạn

SessionManager có API legacy `reconnect(playerId, token, newSocket)`, token và
grace timer; có unit test cục bộ cho API đó. ConnectionManager/ClientHandler
capture socket và kiểm tra ownership khi cleanup. Đây là các mảnh hỗ trợ,
chưa tạo thành luồng reconnect end-to-end.

Ứng dụng hiện ABORTED khi ai đó disconnect/leave, dọn runtime và bỏ ghế ngay.
Metadata session có thể giữ 15 giây rồi hard drop, nhưng không còn trận để resume.
Chỉ thêm handler RECONNECT sẽ không thể khôi phục world/room đúng.

## Phạm vi cần thống nhất trong một lượt riêng

1. **Chính sách trận:** có giữ ghế/world trong grace không, player đứng yên hay
   bị loại, có thể chịu damage trong lúc mất mạng không, hết hạn xử kết quả thế nào.
   Cần thay rõ luật ABORTED hiện tại nếu muốn resume cùng match.
2. **Xác thực phiên:** schema request/response, hạn và cách thay token, chống
   dùng lại, rate limit, hai reconnect đồng thời chỉ một kết nối được nhận.
   Không tin playerId client tự khai để chiếm ghế.
3. **Socket/writer và generation:** attach socket mới với writer mới một cách
   nguyên tử; vô hiệu reader/writer cũ. EOF, write failure và expiry timer cũ
   không được hard drop phiên mới. API legacy chỉ cancel timer theo playerId,
   chưa chứng minh an toàn nếu task expiry đã bắt đầu chạy khi reconnect xảy ra.
4. **MOVE/ACK và requestId:** giữ sequence theo match/player khi resume; không
   reset về 1 như trận mới. Chốt input cuối đã ACK, loại lệnh trùng/cũ và pending
   không còn hợp lệ. PLANT_BOMB cũng phải giữ dedupe để không đặt lại bom sau resume.
5. **Resync:** handshake resume → match context hiện tại → full baseline có
   map/player/alive/item/stats/bom/vùng nổ. Chỉ bật input sau full; lọc epoch cũ,
   không áp dụng pickup/damage/event lần hai. Nếu trận đã kết thúc trong grace,
   gửi kết quả đã chốt thay vì mở lại simulation.
6. **Kiểm thử bắt buộc:** reconnect trước/sau hạn, token sai/dùng lại, đồng thời,
   old socket EOF/failure muộn, timer cũ chạy muộn, disconnect trong frame,
   MOVE/ACK/requestId qua resume, còn bom/item và GAME_OVER trong lúc mất mạng.

Các mục trên chưa được coi là đã hoàn thành nhờ API legacy hoặc test session
đơn lẻ. Không bật route hay tự chọn chính sách giữ trận trong nhiệm vụ 9.

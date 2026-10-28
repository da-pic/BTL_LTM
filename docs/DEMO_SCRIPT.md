# Kịch bản demo và bằng chứng

Chuẩn bị: một server, 2–4 client có desktop; cùng ZIP/SHA256, Java 25+,
IP server và TCP 8888 hoạt động. Chạy theo [LAN_GUIDE.md](LAN_GUIDE.md).
Nhóm phân công một người điều khiển server/ghi log, một người trình bày, các người còn lại chơi.
Đây là **kịch bản để nhóm chạy**, chưa có video/nghiệm thu GUI nhiều máy từ môi trường hiện tại.

| Thời gian | Thao tác | Kết quả cần quan sát/ghi lại |
|---|---|---|
| 0–1 phút | Hiện BUILD_INFO/SHA, khởi động server và 2 client | Connect thành công, danh sách phòng |
| 1–2 phút | Create/Join, chat, khách Ready, host Start | Cùng phòng và cùng map; chưa ready thì không Start |
| 2–4 phút | Di chuyển, đặt bom cạnh block; người đặt chạy tránh | Cả hai cùng thấy bom, nổ sau fuse, block biến mất; server quyết định collision |
| 4–5 phút | Nhặt item nếu có, quan sát HUD | Item chỉ thuộc một người; quota/range/speed đổi thống nhất |
| 5–6 phút | Một người đứng trong vùng nổ, người kia chạy tránh | Chết, WIN và bảng kết quả nhất quán |
| 6–7 phút | Ready/Start lượt mới; rồi host End round | Reset bom/item/stats/time; End cho ABORTED |
| 7–8 phút | Chạy 4 client; ngắt mạng một client giữa trận | Giữ chỗ 30 giây; trận tiếp tục; có mạng lại tự khôi phục; quá hạn loại riêng người đó |
| 8–9 phút | Hiện CSV 1/2/4 client và phương pháp đo | Phân biệt loopback thực đo với LAN do nhóm bổ sung |

Drop mặc định 75%, không cam kết mỗi block đều sinh item. Để trình diễn item/timeout ổn định,
có thể mở terminal server với cấu hình minh họa dưới đây, ghi rõ đã đổi luật so với mặc định:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbombit.item.dropChance=1.0 -Dbombit.match.durationMillis=30000 -Dbombit.match.initialLives=1'
cmd /c scripts\run-server.cmd 8888
```

Cấu hình theo [TASK8_ITEMS_RESULTS.md](TASK8_ITEMS_RESULTS.md);
nếu cần dùng cấu hình mặc định thì gỡ biến trong terminal đó bằng `Remove-Item Env:JAVA_TOOL_OPTIONS`.
Cho tất cả sống đến hết giờ để demo DRAW timeout. Cùng chết và tranh item cần phối hợp vị trí/thời điểm;
các ca này đã có kiểm thử tự động, nhóm vẫn phải quan sát GUI nếu đưa vào video nghiệm thu.

Nếu mạng/GUI lỗi, ghi bước, thời điểm, log và revision; dùng lại CSV loopback đã lưu để giải thích
phương pháp, không gán nó thành số liệu LAN.

Reconnect mặc định bật ở lobby. Giữ ứng dụng client đang mở; tắt Wi-Fi/rút cáp trên
máy client LAN rồi bật lại trong 30 giây kể từ khi server phát hiện mất kết nối.
Nhân vật đứng yên và vẫn có thể trúng bom. Nếu trận kết thúc trong lúc mất mạng,
client nhận kết quả khi kết nối lại. Quá hạn, người đó bị loại; nếu chỉ còn một
người sống thì trận kết thúc theo luật thắng bình thường.
Loopback `127.0.0.1` không bị ảnh hưởng bởi tắt Wi-Fi. Đóng/End task client chỉ demo
loại người hết hạn, không demo tự kết nối lại vì ứng dụng đã ngừng chạy.

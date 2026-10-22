# Chạy LAN và nghiệm thu nhiều máy

Ngày bàn giao: 22/10/2026. **Chưa đo trên nhiều máy LAN trong môi trường bàn giao.**
CSV loopback không chứng minh firewall, Wi-Fi hay giao diện trên máy khác hoạt động.

## Chuẩn bị và chơi bằng giao diện

1. Mỗi máy cài Java 25+; giải nén cùng bản ZIP theo [PACKAGING.md](PACKAGING.md).
2. Máy server chạy `ipconfig`, chọn IPv4 của adapter LAN/Wi-Fi đang dùng, ví dụ `192.168.1.10`.
   Các máy phải liên lạc được với nhau; guest Wi-Fi có thể chặn client với client.
3. Trên server, mở terminal tại thư mục giải nén và chạy:

   ```powershell
   cmd /c scripts\run-server.cmd 8888
   ```

   Server lắng nghe các interface. Nếu Windows hỏi firewall, cho phép Java trên mạng Private
   phù hợp, hoặc cấu hình inbound TCP 8888 theo chính sách máy. Script không tự sửa firewall.
4. Trên mỗi máy client chạy, thay địa chỉ và tên:

   ```powershell
   cmd /c scripts\run-client.cmd 192.168.1.10 8888 Mai
   ```

5. Connect → một người Create → người còn lại chọn phòng/Join → khách Ready → host Start.
   Dùng phím mũi tên và Space. Nếu phím chưa nhận, bấm vào vùng gameplay.
   Thử lần lượt 2 rồi 4 client; xác nhận cùng bom, vùng nổ, block, item, người chết và kết quả.
6. Ready/Start lại; kiểm tra bom/item/chỉ số/thời gian của lượt trước không còn.
   Thử Leave hoặc ngắt một client giữa trận: cả phòng kết thúc lượt với ABORTED.
   Không có tự động reconnect; Connect lại là một kết nối mới.

Luật mới: [5 item, khiên, mạng và hồi sinh](EXTENDED_ITEMS_LIVES.md). Mặc định 3 mạng,
180 giây, range 1 và drop 75%; GUI cần kiểm tra cả hồi sinh tại spawn và mất khiên.

Không dùng `127.0.0.1` để kết nối sang máy khác. Lỗi Connect: kiểm tra IP, server còn chạy,
port, firewall và AP isolation trước; không cần mở cổng router ra Internet.

## Đo bằng runner

Không chạy runner chung phòng với người đang điều khiển GUI. Runner tự tạo/join/ready/start.
Một file CSV cho mỗi tiến trình; không để nhiều máy cùng append một file dùng chung.

Đo 1 client là transport (CONNECT/PING/PONG), vì gameplay yêu cầu tối thiểu 2 người:

```powershell
cmd /c scripts\run-benchmark.cmd --host 192.168.1.10 --port 8888 --clients 1 --mode transport --seconds 60 --warmup 5 --csv lan-transport-A.csv --label LAN-machine-A --revision RELEASE_ID --run-id lan-01
```

Đo 4 client trên 4 máy: trên máy A chạy host runner trước; nó in `ROOM_ID=UUID`
và đợi tối đa 60 giây cho khách ready:

```powershell
cmd /c scripts\run-benchmark.cmd --host 192.168.1.10 --port 8888 --clients 1 --expected-clients 4 --mode movement --seconds 60 --warmup 5 --csv lan-movement-A.csv --label LAN-machine-A --revision RELEASE_ID --run-id lan-02
```

Máy B/C/D chạy cùng lệnh sau, thay tên file/label riêng và UUID vừa in:

```powershell
cmd /c scripts\run-benchmark.cmd --host 192.168.1.10 --port 8888 --clients 1 --expected-clients 4 --room-id UUID --mode movement --seconds 60 --warmup 5 --csv lan-movement-B.csv --label LAN-machine-B --revision RELEASE_ID --run-id lan-02
```

Đổi `--expected-clients 2` để đo 2 máy. Lặp cả `idle`, `movement`, `bombs`, ít nhất 3 lượt/mức tải;
mọi runner cùng mode/seconds/warmup/expected-clients/run-id. Dùng run-id mới cho lượt mới.
`bombs` đặt một bom/người ở đầu cửa sổ, sau đó chạy tránh; không phải đặt bom liên tục.
Một runner có thể dùng `--clients 2` hoặc `4` để tạo tải từ một máy, nhưng phải ghi đúng topology.
Không dùng `--local` khi đo server LAN: tùy chọn đó tạo server loopback riêng.

RTT và MOVE ACK dùng đồng hồ monotonic của từng client, không cần đồng bộ đồng hồ hệ thống.
Các cửa sổ giữa máy chỉ xấp xỉ đồng thời; runner phân tán đợi thêm 1,5 giây ngoài cửa sổ đo
trước khi đóng để giảm ảnh hưởng peer kết thúc trước. Không cộng số đo như thể một capture đồng bộ tuyệt đối.
Xem [phương pháp đo](NETWORK_MEASUREMENTS.md) trước khi diễn giải số liệu.

## Biên bản nhóm cần điền

Lưu CSV gốc, BUILD_INFO, SHA256 ZIP và log lỗi; ghi ngày, OS/Java/CPU/RAM, Ethernet/Wi-Fi,
số máy vật lý, máy nào chạy server/client, thời lượng, revision và thay đổi cấu hình.
Không thay số trống bằng 0 hoặc đánh dấu đạt khi thiếu bằng chứng.

| Mục | Kết quả / bằng chứng nhóm bổ sung |
|---|---|
| 1 transport, 2 và 4 gameplay qua các máy | Chưa chạy |
| RTT/ACK/băng thông 3 lượt mỗi kịch bản | Chưa chạy; đính CSV |
| Cùng bom/nổ/block/item/kết quả qua GUI | Chưa chạy; video/ảnh |
| Tranh item, cùng chết, timeout, chơi lại | Chưa chạy thủ công |
| Rút mạng/đóng client, phòng khác tiếp tục | Chưa chạy trên LAN |
| Soak ≥30 phút, CPU/RAM/thread sau nhiều lượt | Chưa chạy |
| Người chạy, ngày, bản ZIP và kết luận | Chưa xác nhận |

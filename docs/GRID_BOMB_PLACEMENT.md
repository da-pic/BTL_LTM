# Lưới map và ô đặt bom

Theo lựa chọn của người dùng: **nhân vật di chuyển mượt, bom đặt theo ô**.
Map mặc định là lưới ô 32×32 pixel. Movement/hitbox dùng tọa độ liên tục để di chuyển mượt;
bom lưu TilePosition nguyên (cột, hàng), renderer vẽ bom ở tâm ô, tia nổ duyệt theo ô.
Profile mới căn hitbox 24×24 giữa ô, tốc độ 80 và giày +8; [chi tiết](MOVEMENT_BALANCE.md).

## Quy tắc ô của nhân vật

Ô hiện tại là ô chứa **tâm hitbox** trên server. Cạnh trái/trên thuộc ô, cạnh phải/dưới thuộc ô kế tiếp.
Với hitbox rộng 24 và ô rộng 32, x là cạnh trái nhân vật:

| x nhân vật | Tâm x | Cột nội bộ (bắt đầu từ 0) |
|---:|---:|---:|
| 51.999 | 63.999 | 1 |
| 52 | 64 | 2 |
| 52.001 | 64.001 | 2 |

Tương tự cho trục y. Nhân vật có thể nằm vắt qua hai ô; chỉ khi tâm vượt cạnh ô mới đổi ô đặt bom.
Lệnh PLANT_BOMB chỉ gửi requestId, server lấy ô từ vị trí nhân vật khi xử lý lệnh,
kiểm tra ô trống/bom có sẵn/quota rồi tạo bom tại ô đó. Client không chọn tọa độ đặt bom.

Quy tắc lấy ô đã có trước lượt này; lượt này gom phép xác định ô vào TilePosition.containing,
dùng chung cho Player và phần đánh dấu trên client, bổ sung test đúng sát ranh giới.

## Hiển thị

- Map có đường kẻ lưới và nền xen kẽ để phân biệt từng ô.
- Viền xanh đánh dấu ô nhân vật cục bộ theo snapshot server gần nhất.
- Dòng phía trên map hiển thị cột/hàng từ **1** cho dễ đọc, cùng hướng dẫn Space.
- Nhân vật vẫn di chuyển mượt; bom luôn neo theo ô. Qua mạng, snapshot/viền xanh có thể trễ nhẹ
  so với nhân vật đang được client dự đoán; ô đặt thực tế do server quyết định khi xử lý lệnh.

## File và kiểm chứng

Sửa TilePosition.java, Player.java, MovementGameWindow.java; thêm TileGridRenderer.java.
Build/package hỗ trợ ArtifactName trong scripts/build.ps1 và scripts/package.ps1,
có hướng dẫn ở PACKAGING.md, để tạo bản mới khi JAR hiện tại đang được sử dụng trên Windows.
Test: BombGameplayTest thêm các ca trước/đúng/sau đường biên và góc giao bốn ô;
TileGridRendererTest kiểm tra đường kẻ/ô đánh dấu bằng ảnh offscreen.
Ảnh kiểm tra cục bộ: `target/tile-grid-preview.png`.

- [x] Giữ movement mượt, bomb model và placement dùng tọa độ ô nguyên.
- [x] Quy tắc ô chứa tâm thống nhất giữa server và viền xanh.
- [x] Lưới rõ, có nhãn cột/hàng và đánh dấu ô hiện tại.
- [x] Kiểm thử placement trên hai trục tại ranh giới và renderer offscreen.
- [ ] Nhóm nghiệm thu Swing trực tiếp/LAN, nhất là khi di chuyển qua đường biên rồi bấm Space.

Kết quả thực chạy trên JDK 25: **125 JUnit test PASS, 0 failure/error/skipped**;
Check/Demo TV2 và NetworkTest PASS. Ảnh offscreen đã được xem để kiểm tra lưới và viền xanh.
Chưa chạy cửa sổ Swing trực tiếp hoặc nghiệm thu nhiều máy LAN trong lượt này.

JAR tên chuẩn đang bị tiến trình đang chạy khóa nên build lần đầu không ghi đè được.
Đã build riêng `target/bomb-it-multiplayer-grid.jar`, đóng vào ZIP với tên runtime chuẩn;
checksum, class renderer và smoke sau giải nén đều PASS. Không dừng tiến trình của người dùng.
Để xem lưới mới, dừng phiên cũ khi thuận tiện, giải nén ZIP mới sang thư mục khác và chạy từ đó.
Launcher ở source vẫn chọn JAR tên chuẩn cũ cho tới khi được rebuild sau khi hết khóa.

Log hồi quy: `grid-tests.log`. Bước tiếp theo: chạy server/client từ bản build mới,
đứng sát biên hai ô và xác nhận bom nằm giữa đúng ô được server tính cho nhân vật.

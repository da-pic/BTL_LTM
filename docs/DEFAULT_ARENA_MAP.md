# Map gameplay mặc định

Chỉnh bố cục tại `src/main/java/com/bombit/server/game/map/DefaultArenaMap.java`, hằng `LAYOUT`.
LobbyGameServer tạo map qua `DefaultArenaMap.create()` và lấy bốn điểm spawn từ `SPAWNS` cùng file.
Hình nền/tường/thùng được vẽ tại `client/game/movement/TileGridRenderer.java`.

Trước lượt này lobby dùng chung `DemoGameServer.map()`, một map thưa chỉ có hai block mềm.
Hàm đó giờ chỉ dành cho profile demo di chuyển M1; map gameplay mặc định được tách riêng để dễ sửa.

## Bố cục mới

17 cột × 15 hàng, ô 32 pixel; `#` là đá không phá được, `B` là thùng gỗ phá được,
`.` là ô trống. Mỗi dòng phải đủ 17 ký tự, tổng cộng 15 dòng; factory kiểm tra ký tự/kích thước.

```text
#################
#...............#
#.#.#.#.#.#.#.#.#
#.BBBB.....BBBB.#
#.#B#B#.#.#B#B#.#
#....BB.B.BB....#
#.#B#.#.#.#.#B#.#
#...............#
#.#B#.#.#.#.#B#.#
#....BB.B.BB....#
#.#B#B#.#.#B#B#.#
#.BBBB.....BBBB.#
#.#.#.#.#.#.#.#.#
#...............#
#################
```

Tổng 102 ô đá (60 ô viền + 42 trụ), 38 thùng gỗ, 115 ô trống.
Đối xứng ngang/dọc để các góc spawn có cùng điều kiện. Các trụ đá xen kẽ chia hành lang;
đường vòng ngoài và trục ngang giữa cho phép di chuyển giữa các vùng mà không bị kẹt.
Toàn bộ ô trống ban đầu nối được với nhau; thùng tạo vật cản và nguồn item trong các nhánh bên.

Spawn theo cột/hàng **bắt đầu từ 0**: P1=(1,1), P2=(15,1), P3=(1,13), P4=(15,13).
Hitbox 24×24 hiện được căn giữa ô 32×32, chừa 4 px mỗi cạnh; tốc độ mặc định 80 px/s.
Xem [điều chỉnh movement](MOVEMENT_BALANCE.md) cho giày +8 và cấu hình.
Từ mỗi spawn có hai nhánh trống dài ít nhất hai ô để thoát bom range 1 đầu trận.
Không có item được đặt sẵn. Mỗi lượt tạo GameMap mới, nên block bị phá không biến mất ở lượt sau.

Đá xám có đường ghép/viền nổi; thùng gỗ nâu có thanh chéo và đinh. Giao diện có chú giải,
giữ lưới và viền xanh ô nhân vật. Luật bom/item, movement mượt và kích thước map không đổi.

## File thay đổi và nghiệm thu

- Mới: DefaultArenaMap.java và DefaultArenaMapTest.java.
- Sửa: LobbyGameServer.java (factory/spawn), DemoGameServer.java (chú thích map M1),
  TileGridRenderer.java (đá/thùng), MovementGameWindow.java (chú giải), README và tài liệu này.
- [x] Bố cục đối xứng, viền/trụ đá cố định, mật độ thùng được kiểm tra.
- [x] Spawn có hai hướng thoát và tất cả ô trống liên thông, kiểm tra bằng duyệt lưới.
- [x] Phá thùng ở một map không ảnh hưởng map tạo cho trận mới.
- [ ] Nhóm kiểm chứng độ dễ nhìn, nhịp trận và cân bằng bằng chơi GUI/LAN thực tế.

Ảnh xem trước từ chính factory và renderer: `target/default-arena-preview.png`.
Kết quả thực chạy trên Windows/JDK 25: **128 JUnit tests PASS, 0 failure/error/skipped**,
gồm 3 test map mới; Check/Demo TV2 và NetworkTest PASS. Ảnh offscreen đã được xem.
GUI trực tiếp và LAN chưa được nghiệm thu trong lượt này.
ZIP mới đã được kiểm tra SHA256, có đúng DefaultArenaMap.class và chạy workload bom
4 client sau giải nén đạt PASS/cleanup=true. CSV: `target/arena-release-smoke.csv`.

Build bản riêng bằng `scripts/package.cmd -ArtifactName bomb-it-multiplayer-arena.jar`
để tránh ghi đè JAR chuẩn có thể đang được người dùng chạy. ZIP vẫn dùng tên JAR runtime chuẩn.
Giải nén ZIP mới sang thư mục khác và chạy server/client từ đó; launcher source còn ưu tiên JAR chuẩn cũ
cho đến khi bạn đóng phiên cũ và rebuild tên chuẩn.

Log hồi quy: `map-tests.log`, log đóng gói: `map-package.log`. Bước tiếp theo: chạy bản mới, thử né bom ở cả bốn góc,
phá thùng nhận item và Ready/Start lại để kiểm tra reset map.

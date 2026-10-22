# Hitbox và tốc độ mặc định

Ngày 22/10/2026: giữ movement mượt và luật bom theo ô, điều chỉnh profile gameplay lobby để dễ căn hướng.

| Thông số | Trước | Hiện tại |
|---|---:|---:|
| Ô map | 32×32 px | 32×32 px |
| Hitbox | 24×24 px | 24×24 px |
| Spawn trong ô | Cạnh trái/trên ô | Căn giữa: cách mỗi cạnh 4 px |
| Tốc độ ban đầu | 150 px/s | 80 px/s |
| Mỗi giày | +25 px/s | +8 px/s |
| Trần tốc độ | 300 px/s | 144 px/s |

Ô vốn đã lớn hơn hitbox 8 px; giữ kích thước và sửa căn giữa thay vì thay tileSize/map layout.
Ở tick/input 50 ms, tốc độ 80 đi 4 px, qua một ô cần 8 mẫu (0,4 giây) khi không bị cản.
Mỗi giày tăng quãng đi mỗi mẫu 0,4 px; trần 144 tương ứng 7,2 px/mẫu.
Đây là giảm độ nhạy điều khiển, không thêm tự động bẻ góc hoặc di chuyển theo từng ô.

## Giữ logic ô nhất quán

- Ô hiện tại vẫn là ô chứa tâm hitbox: TilePosition.containing; không lấy cạnh trái làm ô đặt bom.
- Spawn dùng `cell * tileSize + (tileSize - hitboxSize) / 2` trên cả hai trục. Hồi sinh về vị trí spawn đó.
- Bomb placement, range, pickup, damage và collision vẫn dùng tileSize/hitbox thực tế của world.
- Client nhận width/height/speed từ PlayerData trong snapshot; prediction dùng cùng MovementSystem/CollisionSystem.
- Không thay version protocol/snapshot hay tốc độ tick. Speed mới được đồng bộ bằng field speed đang có.
- Geometry được test thêm với tileSize 32/40/48, không giả định mọi phép tính đều cố định 32.

## Cấu hình

`PlayerMovementRules.java` giữ hitbox/tốc độ spawn; `MatchRules.java` giữ mức tăng/trần giày.
Thuộc tính server, đọc khi khởi động:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbombit.player.hitboxSize=24 -Dbombit.player.initialSpeed=80 -Dbombit.item.speedIncrement=8 -Dbombit.item.maxSpeed=144'
cmd /c scripts\run-server.cmd 8888
```

Hitbox phải dương và nhỏ hơn tileSize; tốc độ/mức tăng hữu hạn và dương; maxSpeed phải >= initialSpeed.
Không sửa biến môi trường hệ thống; ví dụ áp dụng cho terminal hiện tại.
Tùy chỉnh khác cần chơi thử lại khả năng rẽ/né bom. Profile M1 movement legacy và fixture có tham số
riêng giữ tốc độ explicit của chúng; profile gameplay/lobby và benchmark local dùng mặc định mới.

## File và checklist

- Mới: PlayerMovementRules.java, MovementBalanceTest.java và tài liệu này.
- Sửa: LobbyGameServer.java (profile spawn), MatchRules.java (giày/cap), NetworkBenchmark.java,
  fixture và test TCP BombClientIntegrationTest/MatchLifecycleIntegrationTest/BombRuntimeIntegrationTest,
  ảnh preview trong DefaultArenaMapTest, README, docs/protocol.md và EXTENDED_ITEMS_LIVES.md.
- Fixture gameplay cũ đặt rõ profile tốc độ 150 để giữ ý nghĩa kịch bản cũ; test profile mới dùng 80.
- [x] Hitbox nhỏ hơn ô, spawn/respawn căn giữa, ranh giới ô vẫn theo tâm.
- [x] Server và prediction đi/rẽ cùng kết quả trên map có tường cứng/thùng gỗ.
- [x] Giày tăng 8, giới hạn 144; client dùng tốc độ authoritative để dự đoán.
- [x] Hai client TCP cùng nhận 80 → 88 sau pickup, full resync/duplicate không tăng thêm lần nữa.
- [ ] Nhóm nghiệm thu cảm giác phím/rẽ góc bằng Swing và LAN thực tế.

## Kết quả thực chạy

Windows, JDK 25.0.1, Maven 3.9.12 offline: **133 JUnit tests PASS, 0 failure/error/skipped**,
gồm 4 test MovementBalanceTest mới và 1 test TCP mới, bổ sung kiểm tra default bootstrap trên hai client.
Check/Demo TV2 và NetworkTest main đều PASS. Log: `movement-balance-tests.log` và target/surefire-reports.
Ba test map được chạy lại sau khi cập nhật preview spawn căn giữa, đều PASS (`movement-balance-preview.log`).
ZIP mới đã giải nén và chạy 4 client/bombs: mỗi client nhận đủ 4 bom/4 vụ nổ, PASS/cleanup=true;
checksum ZIP và class PlayerMovementRules trong JAR đã kiểm tra. CSV: `target/movement-balance-release-smoke.csv`.
Log build/package: `movement-balance-package.log`.
Test mới từng gặp import trùng model/legacy enum, đã sửa import rồi chạy lại cả bộ hồi quy đạt.

Build/package dùng tên `bomb-it-multiplayer-movement.jar` để không ghi đè JAR cũ đang chạy;
trong ZIP vẫn là tên runtime chuẩn. Dùng ZIP mới giải nén sang thư mục khác để nhận cấu hình mới.
Không lấy số đo mạng cũ ở target 17 hoặc tốc độ 150 làm số đo của profile này.

Bước tiếp theo: chạy bản mới, thử đi/rẽ trong hành lang, nhặt giày, đặt bom sát ranh giới ô và hồi sinh.

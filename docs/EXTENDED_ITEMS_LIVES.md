# Năm item cơ bản, mạng và hồi sinh

Luật hiện hành sau bổ sung ngày 22/10/2026; Java 25. Server quyết định mọi pickup,
sát thương, mạng, vị trí hồi sinh và kết quả. Không có item trên map lúc bắt đầu trận.

| Item / hình vẽ | Hiệu ứng | Mã wire cố định |
|---|---|---:|
| Bình năng lượng — FIRE_UP | Range +1 trên cả bốn hướng, trần 8 | 1 |
| Quả bom — BOMB_UP | Số bom được đặt đồng thời +1, trần 5 | 0 |
| Trái tim — LIFE_UP | Mạng còn lại +1, trần mặc định 99 | 3 |
| Đôi giày — SPEED_UP | Tốc độ +8 pixel/giây, trần 144 | 2 |
| Khiên — SHIELD | Chặn một đợt sát thương rồi mất; nhặt thêm không cộng dồn | 4 |

Mặc định range=1: ô đặt bom và một ô mỗi hướng, tổng 5 ô hình chữ thập nếu không bị tường/block chặn.
Mỗi bình tăng chiều dài **mỗi nhánh** một ô (range=2 là 9 ô khi trống). Bom đã đặt giữ range lúc đặt.
Quota ban đầu 1, speed 80 pixel/giây. Các nâng cấp được giữ khi hồi sinh và reset khi chơi lượt mới.
Ô 32×32, hitbox 24×24 được căn giữa khi spawn; xem [điều chỉnh movement](MOVEMENT_BALANCE.md).

Mặc định 3 mạng/người, 180 giây/trận. Mất một mạng mà vẫn còn mạng: hồi sinh ngay tại vị trí spawn
ban đầu, dừng movement; chỉ bị loại khi mạng về 0. Nhặt tim không hồi sinh người đã bị loại.
Một người còn mạng thắng, tất cả hết mạng cùng đợt nổ hòa; timeout và ABORTED giữ luật trước.

Các tia/bom nổ cùng tick, kể cả chain reaction, gộp thành **một đợt sát thương/người**.
Khiên chặn cả đợt đó; hồi sinh không bị trừ thêm mạng bởi một tia khác trong cùng đợt.
Không có bất tử có thời hạn sau hồi sinh; đợt nổ ở tick sau vẫn có thể gây sát thương.
Vùng lửa còn hiển thị là animation, không tự gây thêm damage ở client hoặc mỗi frame.

Item chỉ được xét rơi tại ô block mềm vừa phá, **75%** mặc định, chia đều 5 loại trong lần rơi.
Không xét drop cho ô trống hoặc tường cứng; một block/batch chỉ xét một lần.
Item cũ trong vùng nổ bị xóa trước khi tạo drop mới; drop mới không bị cùng batch phá lần hai.
Pickup theo tâm hitbox server; tranh cùng tick thì playerId nhỏ hơn nhận, chỉ cộng hiệu ứng một lần.
Nhặt khiên đang có khiên hoặc item đã chạm trần vẫn tiêu thụ item, không cộng quá giới hạn.

## Cấu hình server cho map mặc định

Các thuộc tính được đọc khi tạo server và áp dụng giống nhau cho phòng mới. Ví dụ:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dbombit.match.initialLives=3 -Dbombit.match.durationMillis=180000 -Dbombit.item.dropChance=0.75'
cmd /c scripts\run-server.cmd 8888
```

Muốn demo nhanh: chọn `initialLives=1`, `durationMillis=30000`, `dropChance=1.0`.
Khởi động lại server khi đổi cấu hình. Gỡ biến trong terminal bằng `Remove-Item Env:JAVA_TOOL_OPTIONS`.
Đây là cấu hình server, chưa có màn hình chọn luật riêng cho từng phòng.

| Property | Mặc định |
|---|---:|
| bombit.match.initialLives | 3 |
| bombit.match.durationMillis | 180000 |
| bombit.item.dropChance | 0.75 |
| bombit.item.maxLives | 99 |
| bombit.item.maxBombs / maxRange / maxSpeed | 5 / 8 / 144 |
| bombit.item.speedIncrement | 8 |
| bombit.player.hitboxSize / initialSpeed | 24 / 80 |
| bombit.bomb.range / quota / fuseMillis | 1 / 1 / 3000 |

Mạng ban đầu phải dương và không vượt maxLives; thời lượng (ms) dương, tối đa 24 giờ;
dropChance trong [0,1]. Constructor MatchRules 6 tham số giữ profile một mạng cho test/legacy;
ứng dụng mặc định dùng MatchRules.demo với 3 mạng, benchmark local cũng dùng profile 3 mạng.

## Đồng bộ và mở rộng

Snapshot **v5** truyền thêm `lives:int32,shield:boolean,respawns:int32` sau ba trường Stats v4,
ở cả full và delta. Client mới đọc v2/v3/v4/v5; v4 cũ suy ra 1 mạng/không khiên/respawns=0.
Client cũ không đọc được v5: phân phối lại server và client từ cùng ZIP.
HUD và nhãn người chơi hiển thị mạng; khiên có vòng viền xanh. Hồi sinh reset pending prediction
và lịch sử interpolation để không replay input cũ hoặc nội suy qua map; sequence input không tái sử dụng.
PLAYER_DIE chỉ được phát khi bị loại, không phát khi khiên chặn hoặc hồi sinh còn mạng.
Partial damage/respawn được đồng bộ qua snapshot; client không chạy lại hiệu ứng từ event.

Để thêm loại sau này: thêm ItemType với **wireId mới**, đăng ký effect trong ItemEffectRegistry,
thêm icon (renderer có fallback), rồi test protocol/pickup. Không đổi mã các loại hiện có.
ItemEffectRegistry là catalog bất biến, có thể inject effect khác vào ItemWorldAdapter;
ItemSystem và luồng pickup/world không cần thêm switch. ItemSpawnPolicy vẫn inject được cho
drop có trọng số hoặc luật map khác; policy mặc định chọn đều các ItemType đang đăng ký.

## File thay đổi và checklist

- Model/luật: Player, MatchRules, MatchGameplayExtension, LobbyGameServer (default range),
  BombSystem/BombWorld/BombWorldAdapter (damage theo batch).
- Item: ItemType, ItemEffectRegistry (mới), ItemWorldAdapter; giữ ItemSystem/spawn policy chung.
- Protocol: MatchFrame, SnapshotPacketCodec, docs/protocol.md và PROTOCOL.md.
- Client: ClientWorldState, MovementGameWindow, ItemRenderer, MovementClientController,
  PredictionController và InterpolationController.
- Công cụ/tài liệu: NetworkBenchmark dùng luật mới; README, LAN/demo/technical guide và tài liệu này.
- Test: ExtendedItemsTest (mới), RespawnPredictionTest (mới), MatchLifecycleIntegrationTest,
  GameplayProtocolTest và Java2DRendererTest.

- [x] Năm loại được drop/pickup thực; không spawn item đầu trận, drop tại block vừa phá.
- [x] Bom mặc định hình chữ thập 5 ô, năng lượng mở rộng cả bốn nhánh.
- [x] Khiên không stack; chain chỉ gây một hit/batch; mất mạng hồi sinh, hết mạng mới loại.
- [x] Hai client TCP thống nhất shield/lives/spawn; full resync và duplicate không cộng hiệu ứng lần hai.
- [x] Replay reset mạng/khiên/respawns/entity; snapshot v4 cũ vẫn đọc được.
- [x] Icon offscreen đủ 5 loại khác nhau; prediction giữ sequence khi hồi sinh, remote teleport không nội suy xuyên map.
- [ ] Nghiệm thu hình vẽ/HUD/phím bằng Swing trực tiếp và trên nhiều máy LAN; cân bằng 75%/3 mạng bằng chơi thực tế.

## Kết quả test thực chạy

Windows, JDK 25.0.1, Maven 3.9.12 offline, release 25: **123 JUnit tests đạt, 0 failure/error/skipped**,
gồm 11 test mới so với baseline 112. Check/Demo TV2 và NetworkTest main đều PASS.
Nhóm focused 14 test cho item/protocol/renderer/lifecycle cũng PASS trước hồi quy cuối.
Log: `items-focused.log`, `items-tests.log`, `target/surefire-reports/`.
Build/package mới đã chạy, ZIP/SHA256 được kiểm tra; JAR giải nén có ItemEffectRegistry và
runner 4 client/bombs đạt PASS, mỗi client nhận đủ 4 bom/4 vụ nổ, cleanup=true.
Log package: `items-package.log`; CSV smoke: `target/items-release-smoke.csv`.
Lần chạy đầu của test TCP mới gửi END_GAME thiếu match envelope và thất bại; đã sửa test theo
protocol chính thức rồi chạy lại cả focused và hồi quy, không bỏ qua assertion hoặc test lỗi.

Các kiểm chứng trên là local TCP/offscreen; chưa thay cho nghiệm thu GUI/LAN và đánh giá cân bằng.
Bước tiếp theo: dùng bản build mới trên mọi máy, thử nhặt từng icon, chặn sát thương bằng khiên,
hết một mạng về đúng spawn, hết toàn bộ mạng mới kết thúc, rồi Ready/Start kiểm tra reset.

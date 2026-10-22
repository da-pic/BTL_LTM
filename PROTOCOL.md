# Bomb It Multiplayer Protocol

Đặc tả tích hợp chính thức được duy trì tại [docs/protocol.md](docs/protocol.md).

Bổ sung item/mạng: snapshot v5 mang lives/shield/respawns; client đọc v2–v5.
Item wire ID cố định 0=BOMB_UP, 1=FIRE_UP, 2=SPEED_UP, 3=LIFE_UP, 4=SHIELD.
Phải dùng server/client mới cùng bản; PLAYER_DIE chỉ khi hết mạng và bị loại.

Nhiệm vụ 9 bổ sung giới hạn traffic, deadline ghi và cleanup; không thay mã packet
hoặc layout snapshot. [Báo cáo ổn định](docs/TASK9_STABILITY.md) ghi test và giới hạn;
RECONNECT vẫn chưa được đăng ký trong ứng dụng.

Nhiệm vụ 8 đã nối ITEM_SPAWN/ITEM_PICKED_UP/PLAYER_DIE/GAME_OVER theo schema chính thức.
Snapshot v4 bổ sung HUD và kết quả; client hỗ trợ v2/v3/v4. Xem
[báo cáo nhiệm vụ 8](docs/TASK8_ITEMS_RESULTS.md) cho luật, kết quả test và giới hạn còn lại.

Nhiệm vụ 7 đã nối PLANT_BOMB và event bom theo schema chính thức. Snapshot v3 thêm
vùng nổ còn hiển thị để full resync phục hồi; client hiện hỗ trợ cả v2/v3, body v2 cũ không đổi.
Xem [báo cáo nhiệm vụ 7](docs/TASK7_CLIENT_BOMBS.md) để chạy và xem kết quả kiểm thử.

Từ M0 ngày 21/10/2026, protocol tích hợp dùng version 2, có match envelope;
codec MOVE và body snapshot v2 hiện có được giữ tương thích. Bảng schema cũ ở đây đã được
thay bằng liên kết để tránh hai nguồn đặc tả mâu thuẫn.

Các message được đặc tả không đồng nghĩa handler/bootstrap đã hoàn thành.
Xem [bàn giao M0](docs/M0_INTEGRATION_CONTRACT.md) để biết API đã bổ sung,
phần cần nối ở nhiệm vụ sau và những lựa chọn nghiệp vụ còn chờ.

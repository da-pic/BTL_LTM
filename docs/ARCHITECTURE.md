# Kiến trúc và ranh giới module

```text
                         +-----------------------+
                         |     ClientNetwork     | TV4
                         +-----------+-----------+
                                     |
                               TCP / Packets
                                     |
                         +-----------v-----------+
                         |     Server Network    | TV1
                         +-----------+-----------+
                                     |
                         +-----------v-----------+
                         |     Message Router    | TV4
                         +---+---------------+---+
                             |               |
                       MOVE command     BOMB command
                             |               |
                    +--------v------+ +------v---------+
                    | Movement/Game | | Bomb/Item      |
                    | Core (TV2)   | | Systems (TV3)  |
                    +--------+------+ +------+---------+
                             |               |
                             +-------+-------+
                                     |
                               GameState (TV2)
                                     |
                         Snapshot / Game Events
                                     |
                          Encoder + Room Broadcast
```

## Hợp đồng quan trọng
- TV1 cung cấp API gửi message tới một Client hoặc một Room (hoặc gọi broadcaster đã được thống nhất).
- TV2 sở hữu `GameState` và luồng cập nhật state; command từ Client nên được chuyển vào Game Loop thay vì sửa state trực tiếp từ socket thread.
- TV3 cung cấp hệ thống bomb/item qua API rõ ràng; không sở hữu Game Loop hoặc packet codec.
- TV4 định nghĩa packet/data format và dispatcher; gameplay handler được đăng ký vào dispatcher theo contract đã thống nhất.
- `server/app` chỉ lắp ghép các thành phần, không chứa luật gameplay.

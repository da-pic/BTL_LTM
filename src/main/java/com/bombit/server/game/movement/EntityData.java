package com.bombit.server.game.movement;

import java.util.Objects;

/**
 * Ảnh trạng thái bất biến của một bom hoặc item, phục vụ so sánh delta theo giá trị.
 * kind + id tạo khóa để bomb và item cùng số ID không ghi đè nhau.
 * tileX/tileY dùng đơn vị ô; ownerId là chủ bom hoặc chuỗi rỗng khi không có.
 * variant mang tầm nổ hoặc mã loại item theo quy ước nhóm; expiresAtMillis dùng thời gian server.
 * Gửi mốc hết hạn thay cho countdown đổi liên tục để không tạo delta chỉ vì bộ đếm thời gian.
 */
// Mục đích: Khởi tạo EntityData và kiểm tra dữ liệu đầu vào; xem chú thích của lớp để biết đơn vị và trách nhiệm.
public record EntityData(Kind kind, int id, int tileX, int tileY,
                         String ownerId, int variant, long expiresAtMillis) {
    public enum Kind { BOMB, ITEM }

    public EntityData {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(ownerId);
        if (id <= 0 || tileX < 0 || tileY < 0 || variant < 0) {
            throw new IllegalArgumentException("Invalid replicated entity");
        }
    }
    // Mục đích: Tạo khóa ghép loại và ID, ví dụ BOMB:1 và ITEM:1 là hai entity khác nhau.
    public String key() { return kind.name() + ":" + id; }
}

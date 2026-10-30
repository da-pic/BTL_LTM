package com.bombit.server.game.checkpoint;

/**
 * Một hệ con tham gia checkpoint trên game thread. C phải bất biến và không giữ
 * tham chiếu tới world mutable. Snapshot mạng không thay thế hợp đồng này.
 * validateRestore kiểm tra trước khi coordinator bắt đầu sửa bất kỳ hệ con nào.
 */
public interface CheckpointParticipant<C> {
    C captureCheckpoint();
    void validateRestore(C checkpoint);
    void restoreCheckpoint(C checkpoint);
}

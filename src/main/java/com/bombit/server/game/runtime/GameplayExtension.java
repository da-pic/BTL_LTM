package com.bombit.server.game.runtime;

import com.bombit.server.game.core.GameCommand;

/** Điểm mở rộng TV3, chỉ được gọi trên luồng GameLoop của đúng room.
 * Factory tạo implementation gắn với world trước khi chạy loop. Không giữ thread riêng sửa state.
 * processCommand nhận command theo thứ tự xử lý; update chạy sau movement, trước snapshot.
 */
public interface GameplayExtension {
    /** Called once on the owning game thread, before the first command. */
    default void onStart() { }
    void processCommand(GameCommand command);
    void update(long nowMillis);
    /** Runs on the same game thread, including failure and shutdown. */
    default void onStop() { }
    static GameplayExtension none() {
        return new GameplayExtension() {
            @Override public void processCommand(GameCommand command) { }
            @Override public void update(long nowMillis) { }
        };
    }
}

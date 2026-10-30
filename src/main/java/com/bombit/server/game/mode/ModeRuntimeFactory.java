package com.bombit.server.game.mode;

import com.bombit.common.dto.GameMode;
import com.bombit.common.dto.MatchConfiguration;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.runtime.GameplayExtension;
import java.util.Map;

/**
 * Biên tạo tài nguyên riêng từng trận, không tạo GameLoop hoặc mở socket.
 * Manager validate cấu hình trước khi gọi; factory phải trả map và extension mới mỗi lần.
 * World chỉ được giữ bởi extension của đúng trận, không lưu trong singleton definition.
 */
public interface ModeRuntimeFactory {
    GameMode gameMode();
    default java.util.function.LongSupplier nanoClock(){return System::nanoTime;}
    default java.util.function.LongSupplier epochClock(){return System::currentTimeMillis;}
    GameMap createMap(String roomId, MatchConfiguration configuration);
    GameplayExtension createGameplay(String roomId, MatchConfiguration configuration,
                                     GameState state, Map<String, Integer> players);
}

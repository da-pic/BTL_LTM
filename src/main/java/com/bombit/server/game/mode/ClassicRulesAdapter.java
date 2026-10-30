package com.bombit.server.game.mode;

import com.bombit.common.dto.EffectiveRules;
import com.bombit.common.protocol.IntegrationContract;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.runtime.MatchRules;
import com.bombit.server.game.runtime.PlayerMovementRules;
import java.util.Objects;

/**
 * Biên chuyển rule server hiện hành sang model chung, không làm common/dto phụ thuộc server.
 * Đọc cấu hình một lần khi dựng registry; resolve phòng sau đó không đọc lại system property.
 * LobbyGameServer dùng adapter ở NV05; wire/UI đa mode được ghép riêng ở NV06.
 */
public final class ClassicRulesAdapter {
    private ClassicRulesAdapter() { }

    /** Cùng nguồn/mặc định với LobbyGameServer, giữ property Classic thay vì dùng preset Shadow. */
    public static EffectiveRules.Common serverDefaults() {
        return publicRules(new BombRules(Long.getLong("bombit.bomb.fuseMillis", 3000L),
                Integer.getInteger("bombit.bomb.range", 1), Integer.getInteger("bombit.bomb.quota", 1)),
                MatchRules.demo(), PlayerMovementRules.defaults());
    }

    public static EffectiveRules.Common publicRules(BombRules bombs, MatchRules match, PlayerMovementRules movement) {
        return capture(bombs, match, movement, ArenaGenerator.WIDTH, ArenaGenerator.HEIGHT,
                ArenaGenerator.TILE_SIZE, 50, IntegrationContract.INPUT_STEP_MILLIS,
                IntegrationContract.RECONNECT_GRACE_MILLIS, 29000);
    }

    /**
     * Fixture INTERNAL được truyền hình học/timer tường minh, không đoán từ closure extension cũ.
     * Cross-validation của Common phát hiện quota/range/speed ban đầu vượt trần ngay tại biên.
     */
    public static EffectiveRules.Common capture(BombRules bombs, MatchRules match, PlayerMovementRules movement,
                                               int width, int height, int tilePixels, int tickMillis,
                                               int inputStepMillis, int graceMillis, int recoveryBudgetMillis) {
        Objects.requireNonNull(bombs, "bombs");
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(movement, "movement");
        return new EffectiveRules.Common(width, height, tilePixels, tickMillis, inputStepMillis,
                movement.hitboxSize(), movement.initialSpeed(), match.speedIncrement(), match.maxSpeed(),
                match.initialLives(), match.maxLives(), bombs.fuseMillis(), bombs.quota(), match.maxBombs(),
                bombs.range(), match.maxRange(), match.dropChance(), match.durationMillis(),
                graceMillis, recoveryBudgetMillis);
    }
}

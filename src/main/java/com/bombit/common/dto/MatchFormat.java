package com.bombit.common.dto;

/** Kiểu thi đấu độc lập game mode; INDIVIDUAL vẫn là multiplayer, không phải đấu với bot. */
public enum MatchFormat {
    INDIVIDUAL, TEAM_2V2;

    public static MatchFormat defaultFor(GameMode mode) {
        return mode == GameMode.SHADOW_RELAY ? TEAM_2V2 : INDIVIDUAL;
    }

    /** Slot roster quyết định đội, không phụ thuộc ID hoặc thứ tự packet mạng. */
    public TeamId teamForSlot(int slot) {
        if (slot < 1 || slot > 4) throw new IllegalArgumentException("Invalid roster slot");
        return this == INDIVIDUAL ? null : slot % 2 == 1 ? TeamId.A : TeamId.B;
    }
}

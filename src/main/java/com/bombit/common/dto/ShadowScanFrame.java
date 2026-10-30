package com.bombit.common.dto;

import java.util.List;
import java.util.Map;

/** Ảnh scan trên game thread. Cooldown là dữ liệu riêng: projector chỉ lấy của recipient. */
public record ShadowScanFrame(long matchTimeMillis, List<Pending> pending, List<Active> active,
                              Map<Integer, Long> cooldownReadyAtMillis) {
    public record Pending(int id, int ownerPlayerId, TeamId team, int tileX, int tileY, long activatesAtMillis) { }
    public record Active(int id, int ownerPlayerId, TeamId team, int tileX, int tileY, long expiresAtMillis) { }
    public ShadowScanFrame {
        pending=List.copyOf(pending);active=List.copyOf(active);cooldownReadyAtMillis=Map.copyOf(cooldownReadyAtMillis);
        if(matchTimeMillis<0)throw new IllegalArgumentException("Negative match time");
    }
}

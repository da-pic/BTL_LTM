package com.bombit.common.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Ảnh cấu hình gameplay đã resolve, độc lập theme, room revision và state trận.
 * Construct được DTO không có nghĩa được phép start: server còn phải kiểm ModeRegistry.
 * Toàn bộ đồ thị giá trị đều bất biến, hai phòng có thể chia sẻ giá trị nhưng không chia sẻ mutable state.
 */
public record MatchConfiguration(UUID configurationId, GameMode gameMode, String rulesVersion,
                                 MatchPreset presetId, MatchProfile profile, String layoutId,
                                 EffectiveRules effectiveRules, MatchFormat matchFormat) {
    /** Giữ constructor cũ cho fixture/bootstrap: Classic/Paradox cá nhân, Shadow đội 2–2. */
    public MatchConfiguration(UUID id,GameMode mode,String version,MatchPreset preset,MatchProfile profile,
            String layout,EffectiveRules rules) {
        this(id,mode,version,preset,profile,layout,rules,MatchFormat.defaultFor(mode));
    }
    public boolean isTeamMatch(){return matchFormat==MatchFormat.TEAM_2V2;}
    public TeamId teamForSlot(int slot){return matchFormat.teamForSlot(slot);}
    public MatchConfiguration withFormat(MatchFormat format){
        if(matchFormat==format)return this;
        return new MatchConfiguration(UUID.randomUUID(),gameMode,rulesVersion,presetId,profile,layoutId,effectiveRules,format);
    }
    public MatchConfiguration {
        Objects.requireNonNull(matchFormat,"matchFormat");
        Objects.requireNonNull(configurationId, "configurationId");
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(layoutId, "layoutId");
        Objects.requireNonNull(effectiveRules, "effectiveRules");
        if(gameMode==GameMode.SHADOW_RELAY&&matchFormat!=MatchFormat.TEAM_2V2
                ||profile==MatchProfile.INTERNAL&&matchFormat!=MatchFormat.INDIVIDUAL)
            throw new IllegalArgumentException("Match format incompatible with mode/profile");
        if (gameMode != presetId.gameMode() || !rulesVersion.equals(presetId.rulesVersion())
                || profile != presetId.profile() || !layoutId.equals(presetId.layoutId())) {
            throw new IllegalArgumentException("Mode/rules/preset/profile/layout disagree");
        }
        if ((gameMode == GameMode.CLASSIC && !(effectiveRules instanceof EffectiveRules.Classic))
                || (gameMode == GameMode.SHADOW_RELAY && !(effectiveRules instanceof EffectiveRules.ShadowRelay))
                || (gameMode == GameMode.PARADOX && !(effectiveRules instanceof EffectiveRules.Paradox))) {
            throw new IllegalArgumentException("Effective rules do not belong to this mode");
        }
        var common = effectiveRules.common();
        if (profile == MatchProfile.PUBLIC && (common.width() != 17 || common.height() != 15
                || common.tilePixels() != 32 || common.tickTargetMillis() != 50 || common.inputStepMillis() != 50)) {
            throw new IllegalArgumentException("Public v1 requires 17x15/32px and 50ms steps");
        }
    }
}

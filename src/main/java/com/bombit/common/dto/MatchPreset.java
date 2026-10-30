package com.bombit.common.dto;

/**
 * Liên kết preset với mode/luật/layout để không ghép các chuỗi không tương thích.
 * Tên enum là tên contract; codec tương lai phải dùng name(), không truyền ordinal.
 * Circuit chưa triển khai nên không có preset khả dụng.
 */
public enum MatchPreset {
    CLASSIC_SERVER_DEFAULT(GameMode.CLASSIC, "classic-v1", "classic-generator-v1", MatchProfile.PUBLIC),
    SHADOW_RELAY_V1(GameMode.SHADOW_RELAY, "shadow-relay-v1", "shadow-relay-arena-v1", MatchProfile.PUBLIC),
    PARADOX_V1(GameMode.PARADOX, "paradox-v1", "classic-generator-v1", MatchProfile.PUBLIC),
    CLASSIC_INTERNAL(GameMode.CLASSIC, "classic-v1", "classic-generator-v1", MatchProfile.INTERNAL);

    private final GameMode gameMode;
    private final String rulesVersion;
    private final String layoutId;
    private final MatchProfile profile;

    MatchPreset(GameMode gameMode, String rulesVersion, String layoutId, MatchProfile profile) {
        this.gameMode = gameMode;
        this.rulesVersion = rulesVersion;
        this.layoutId = layoutId;
        this.profile = profile;
    }

    public GameMode gameMode() { return gameMode; }
    public String rulesVersion() { return rulesVersion; }
    public String layoutId() { return layoutId; }
    public MatchProfile profile() { return profile; }
}

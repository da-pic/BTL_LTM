package com.bombit.server.game.mode;

import com.bombit.common.dto.*;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.bombit.server.game.mode.ModeConfigurationException.Code.*;

/**
 * Một nguồn định nghĩa/validate mode phía server, không chứa socket, UI, Room hoặc GameState.
 * Model/definition bất biến; factory dùng chung chỉ giữ hàm tạo, không giữ world của room.
 * Classic, Shadow và Paradox khả dụng; Circuit vẫn bị chặn.
 */
public final class ModeRegistry {
    private final Map<GameMode, ModeDefinition> definitions;
    private final List<ModeDescriptor> descriptors;

    public ModeRegistry() { this(ClassicRulesAdapter.serverDefaults()); }

    /** Cho bootstrap/test truyền snapshot rule tường minh, không cần sửa property toàn tiến trình. */
    public ModeRegistry(EffectiveRules.Common classicRules) {
        this(classicRules, new ClassicModeFactory());
    }

    /** Cho bootstrap/test chọn factory; registry vẫn kiểm mode/availability và luật từ server. */
    public ModeRegistry(EffectiveRules.Common classicRules, ModeRuntimeFactory classicFactory) {
        this(classicRules,classicFactory,new com.bombit.server.game.mode.shadow.ShadowModeFactory());
    }
    public ModeRegistry(EffectiveRules.Common classicRules,ModeRuntimeFactory classicFactory,ModeRuntimeFactory shadowFactory){
        this(classicRules,classicFactory,shadowFactory,new com.bombit.server.game.mode.paradox.ParadoxModeFactory());
    }
    public ModeRegistry(EffectiveRules.Common classicRules,ModeRuntimeFactory classicFactory,ModeRuntimeFactory shadowFactory,ModeRuntimeFactory paradoxFactory){
        var entries = new EnumMap<GameMode, ModeDefinition>(GameMode.class);
        entries.put(GameMode.CLASSIC, ModeDefinition.classic(Objects.requireNonNull(classicRules, "classicRules"), classicFactory));
        entries.put(GameMode.SHADOW_RELAY,ModeDefinition.shadow(Objects.requireNonNull(shadowFactory)));
        if(paradoxFactory!=null)entries.put(GameMode.PARADOX,ModeDefinition.paradox(classicRules,paradoxFactory));
        for (GameMode mode : GameMode.values()) {
            if (!entries.containsKey(mode)) entries.put(mode, ModeDefinition.unimplemented(mode));
        }
        definitions = Map.copyOf(entries);
        // EnumMap bảo đảm thứ tự ổn định, khác iteration order không được hứa của Map.copyOf.
        descriptors = entries.values().stream().map(ModeDefinition::descriptor).toList();
    }

    public List<ModeDescriptor> descriptors() { return descriptors; }

    /** find trả cả metadata unavailable; caller vẫn phải qua resolve/validate trước start. */
    public Optional<ModeDefinition> find(GameMode mode) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(mode, "mode")));
    }

    public MatchConfiguration resolvePublic(GameMode mode) {
        return require(mode).resolveDefault();
    }
    public MatchConfiguration resolvePublic(GameMode mode,MatchFormat format){
        try{return resolvePublic(mode).withFormat(format);}
        catch(IllegalArgumentException e){if(e instanceof ModeConfigurationException known)throw known;throw new ModeConfigurationException(INVALID_CONFIGURATION);}
    }

    public MatchConfiguration resolvePublic(GameMode mode, String rulesVersion, MatchPreset preset) {
        return require(mode).resolve(rulesVersion, preset);
    }

    /** Biên request chưa có codec v3: tên mode/preset sai bị từ chối, không fallback Classic. */
    public MatchConfiguration resolvePublic(String mode, String rulesVersion, String preset) {
        GameMode parsed;
        try { parsed = GameMode.valueOf(mode); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ModeConfigurationException(MODE_NOT_SUPPORTED);
        }
        var definition = require(parsed);
        definition.requireAvailable();
        MatchPreset selected;
        try { selected = MatchPreset.valueOf(preset); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
        return definition.resolve(rulesVersion, selected);
    }

    public void validatePublicConfiguration(MatchConfiguration configuration) {
        if (configuration == null) throw new ModeConfigurationException(INVALID_CONFIGURATION);
        require(configuration.gameMode()).validatePublic(configuration);
    }

    /** Chỉ kiểm luật/số người ở NV04; READY, quyền host và roster/team thuộc NV06/NV07. */
    public void validatePublicStart(MatchConfiguration configuration, int playerCount) {
        validatePublicConfiguration(configuration);
        if(configuration.isTeamMatch()&&playerCount!=4)throw new ModeConfigurationException(INVALID_CONFIGURATION);
        var descriptor = require(configuration.gameMode()).descriptor();
        if (playerCount < descriptor.minPlayers() || playerCount > descriptor.maxPlayers()) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
    }

    public void validateClientFeatures(MatchConfiguration configuration, Set<String> features) {
        validatePublicConfiguration(configuration);
        if (features == null || !features.contains(require(configuration.gameMode()).descriptor().requiredFeature())
                ||configuration.isTeamMatch()&&configuration.gameMode()!=GameMode.SHADOW_RELAY&&!features.contains("TEAM_MATCH_V1")) {
            throw new ModeConfigurationException(CLIENT_CAPABILITY_MISMATCH);
        }
    }

    /**
     * Lối riêng cho fixture Classic một người/một mạng và hình học khác PUBLIC.
     * Không được gọi từ request client; PUBLIC validator luôn từ chối profile này.
     */
    public MatchConfiguration resolveInternalClassic(EffectiveRules.Common rules) {
        return new MatchConfiguration(UUID.randomUUID(), GameMode.CLASSIC, "classic-v1",
                MatchPreset.CLASSIC_INTERNAL, MatchProfile.INTERNAL, "classic-generator-v1",
                new EffectiveRules.Classic(Objects.requireNonNull(rules, "rules")));
    }

    public void validateInternalStart(MatchConfiguration configuration, int playerCount) {
        if (configuration == null || configuration.profile() != MatchProfile.INTERNAL
                || configuration.presetId() != MatchPreset.CLASSIC_INTERNAL || playerCount < 1 || playerCount > 4) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
    }

    private ModeDefinition require(GameMode mode) {
        var definition = mode == null ? null : definitions.get(mode);
        if (definition == null) throw new ModeConfigurationException(MODE_NOT_SUPPORTED);
        return definition;
    }
}

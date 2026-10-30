package com.bombit.server.game.mode;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.runtime.GameplayExtension;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static com.bombit.server.game.mode.ModeConfigurationException.Code.*;

/**
 * Định nghĩa server bất biến: metadata và khả năng resolve cấu hình đã được kiểm tra.
 * NV05 gắn factory Classic; mỗi lần tạo runtime vẫn dùng GameLoop chung của manager.
 * Mode chưa triển khai không có template luật khả dụng và không có công tắc bật từ client.
 */
public final class ModeDefinition {
    private final ModeDescriptor descriptor;
    private final MatchPreset preset;
    private final EffectiveRules rules;
    private final ModeRuntimeFactory runtimeFactory;

    private ModeDefinition(ModeDescriptor descriptor, MatchPreset preset, EffectiveRules rules, ModeRuntimeFactory runtimeFactory) {
        this.descriptor = descriptor;
        this.preset = preset;
        this.rules = rules;
        this.runtimeFactory = runtimeFactory;
    }

    static ModeDefinition classic(EffectiveRules.Common rules) {
        return classic(rules, new ClassicModeFactory());
    }

    static ModeDefinition classic(EffectiveRules.Common rules, ModeRuntimeFactory runtimeFactory) {
        Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        if (runtimeFactory.gameMode() != GameMode.CLASSIC) throw new ModeConfigurationException(INVALID_CONFIGURATION);
        var definition = new ModeDefinition(new ModeDescriptor(GameMode.CLASSIC, "classic-v1", true, null,
                2, 4, 0, "CLASSIC_V1", ModeCapabilities.NONE),
                MatchPreset.CLASSIC_SERVER_DEFAULT, new EffectiveRules.Classic(rules), runtimeFactory);
        // Fail-fast nếu bootstrap đưa hình học INTERNAL vào preset PUBLIC.
        definition.resolve("classic-v1", MatchPreset.CLASSIC_SERVER_DEFAULT);
        return definition;
    }

    static ModeDefinition unimplemented(GameMode mode) {
        boolean shadow = mode == GameMode.SHADOW_RELAY;
        return new ModeDefinition(new ModeDescriptor(mode, shadow ? "shadow-relay-v1" : null,
                false, ModeDescriptor.UnavailableReason.NOT_IMPLEMENTED,
                shadow ? 4 : null, shadow ? 4 : null, shadow ? 2 : null,
                shadow ? "SHADOW_RELAY_V1" : null, ModeCapabilities.NONE), null, null, null);
    }
    static ModeDefinition shadow(ModeRuntimeFactory factory){
        if(factory.gameMode()!=GameMode.SHADOW_RELAY)throw new ModeConfigurationException(INVALID_CONFIGURATION);
        return new ModeDefinition(new ModeDescriptor(GameMode.SHADOW_RELAY,"shadow-relay-v1",true,null,4,4,2,"SHADOW_RELAY_V1",
                new ModeCapabilities(true,true,true,false,false,false)),MatchPreset.SHADOW_RELAY_V1,EffectiveRules.ShadowRelay.v1(),factory);
    }
    /** Chỉ bootstrap thử nghiệm cấp factory ở bước protocol; mặc định chưa bật Paradox cho lobby. */
    static ModeDefinition paradox(EffectiveRules.Common common,ModeRuntimeFactory factory){
        if(factory.gameMode()!=GameMode.PARADOX)throw new ModeConfigurationException(INVALID_CONFIGURATION);
        return new ModeDefinition(new ModeDescriptor(GameMode.PARADOX,"paradox-v1",true,null,2,4,0,"PARADOX_V1",
                new ModeCapabilities(false,false,false,true,true,false)),MatchPreset.PARADOX_V1,new EffectiveRules.Paradox(common),factory);
    }

    public ModeDescriptor descriptor() { return descriptor; }
    public java.util.function.LongSupplier nanoClock(){requireAvailable();return runtimeFactory.nanoClock();}
    public java.util.function.LongSupplier epochClock(){requireAvailable();return runtimeFactory.epochClock();}

    /** Đường PUBLIC kiểm lại toàn bộ rule; INTERNAL chỉ cho fixture Classic được cấp tường minh. */
    private void validateFactoryConfiguration(MatchConfiguration configuration) {
        requireAvailable();
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.profile() == MatchProfile.PUBLIC) validatePublic(configuration);
        else if (descriptor.gameMode() != GameMode.CLASSIC || configuration.presetId() != MatchPreset.CLASSIC_INTERNAL) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
    }

    public GameMap createMap(String roomId, MatchConfiguration configuration) {
        validateFactoryConfiguration(configuration);
        return Objects.requireNonNull(runtimeFactory.createMap(roomId, configuration), "map");
    }

    public GameplayExtension createGameplay(String roomId, MatchConfiguration configuration,
                                             GameState state, Map<String, Integer> players) {
        validateFactoryConfiguration(configuration);
        return Objects.requireNonNull(runtimeFactory.createGameplay(roomId, configuration, state, Map.copyOf(players)), "extension");
    }

    void requireAvailable() {
        if (!descriptor.available()) throw new ModeConfigurationException(MODE_UNAVAILABLE);
    }

    MatchConfiguration resolveDefault() {
        requireAvailable();
        return resolve(descriptor.rulesVersion(), preset);
    }

    /** Server tự cấp configurationId; request không được chọn ID hoặc effectiveRules. */
    MatchConfiguration resolve(String rulesVersion, MatchPreset requestedPreset) {
        requireAvailable();
        if (!Objects.equals(descriptor.rulesVersion(), rulesVersion)) {
            throw new ModeConfigurationException(RULES_NOT_SUPPORTED);
        }
        if (requestedPreset != preset) throw new ModeConfigurationException(INVALID_CONFIGURATION);
        try {
            return new MatchConfiguration(UUID.randomUUID(), descriptor.gameMode(), rulesVersion,
                    preset, MatchProfile.PUBLIC, preset.layoutId(), rules);
        } catch (IllegalArgumentException invalid) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
    }

    /**
     * Kiểm lại tại biên start: một DTO hợp lệ về số học vẫn có thể là cấu hình bị giả mạo.
     * So luật với snapshot registry, không tin presetId/rulesVersion do client tự khai.
     */
    void validatePublic(MatchConfiguration configuration) {
        requireAvailable();
        if (configuration.profile() != MatchProfile.PUBLIC || configuration.gameMode() != descriptor.gameMode()
                || configuration.presetId() != preset || !configuration.rulesVersion().equals(descriptor.rulesVersion())
                || !configuration.layoutId().equals(preset.layoutId()) || !configuration.effectiveRules().equals(rules)) {
            throw new ModeConfigurationException(INVALID_CONFIGURATION);
        }
    }
}

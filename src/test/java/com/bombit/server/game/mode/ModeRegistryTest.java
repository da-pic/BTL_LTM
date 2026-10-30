package com.bombit.server.game.mode;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.IntegrationContract;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.runtime.MatchRules;
import com.bombit.server.game.runtime.PlayerMovementRules;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bombit.server.game.mode.ModeConfigurationException.Code.*;

/** Kiểm registry thật: không khởi động socket/loop để ép mode chưa làm thành khả dụng. */
class ModeRegistryTest {
    private static ModeRegistry registry() { return new ModeRegistry(EffectiveRules.Common.shadowRelayV1()); }

    private static void rejected(ModeConfigurationException.Code code, Runnable action) {
        assertEquals(code,assertThrows(ModeConfigurationException.class,action::run).code());
    }

    @Test void classicIsDistinctFromThemeAndFutureModesHaveNoFakeGameplay() {
        var registry = registry();
        assertEquals(4,registry.descriptors().size());
        assertEquals(GameMode.CLASSIC,registry.descriptors().getFirst().gameMode());
        assertNotEquals((Object)GameMode.CLASSIC,(Object)MapTheme.CLASSIC);
        var classic = registry.find(GameMode.CLASSIC).orElseThrow().descriptor();
        assertTrue(classic.available());
        assertEquals(2,classic.minPlayers()); assertEquals(4,classic.maxPlayers());
        for (var mode : new GameMode[]{GameMode.CIRCUIT}) {
            var descriptor = registry.find(mode).orElseThrow().descriptor();
            assertFalse(descriptor.available());
            assertEquals(ModeDescriptor.UnavailableReason.NOT_IMPLEMENTED,descriptor.unavailableReason());
            assertEquals(ModeCapabilities.NONE,descriptor.capabilities());
            rejected(MODE_UNAVAILABLE,() -> registry.resolvePublic(mode));
        }
        assertNull(registry.find(GameMode.CIRCUIT).orElseThrow().descriptor().rulesVersion());
        var paradox=registry.find(GameMode.PARADOX).orElseThrow().descriptor();assertTrue(paradox.available());
        assertEquals(2,paradox.minPlayers());assertEquals(4,paradox.maxPlayers());assertTrue(paradox.capabilities().rewind());
        var config=registry.resolvePublic(GameMode.PARADOX);assertEquals(MatchPreset.PARADOX_V1,config.presetId());
        assertDoesNotThrow(()->registry.validatePublicStart(config,2));assertDoesNotThrow(()->registry.validatePublicStart(config,4));
        rejected(INVALID_CONFIGURATION,()->registry.validatePublicStart(config,1));
        rejected(CLIENT_CAPABILITY_MISMATCH,()->registry.validateClientFeatures(config,Set.of("CLASSIC_V1")));
        assertThrows(UnsupportedOperationException.class,() -> registry.descriptors().clear());
    }

    @Test void unsupportedNamesVersionsAndPresetsNeverFallback() {
        var registry = registry();
        rejected(MODE_NOT_SUPPORTED,() -> registry.resolvePublic("FOREST","classic-v1","CLASSIC_SERVER_DEFAULT"));
        rejected(MODE_NOT_SUPPORTED,() -> registry.resolvePublic("classic","classic-v1","CLASSIC_SERVER_DEFAULT"));
        rejected(MODE_NOT_SUPPORTED,() -> registry.resolvePublic((GameMode)null));
        rejected(RULES_NOT_SUPPORTED,() -> registry.resolvePublic("CLASSIC","classic-v999","CLASSIC_SERVER_DEFAULT"));
        rejected(INVALID_CONFIGURATION,() -> registry.resolvePublic("CLASSIC","classic-v1","CLASSIC_INTERNAL"));
        rejected(INVALID_CONFIGURATION,() -> registry.resolvePublic("CLASSIC","classic-v1","INVENTED"));
        rejected(INVALID_CONFIGURATION,() -> registry.resolvePublic(GameMode.CLASSIC,"classic-v1",MatchPreset.SHADOW_RELAY_V1));
        assertDoesNotThrow(() -> registry.resolvePublic("SHADOW_RELAY","shadow-relay-v1","SHADOW_RELAY_V1"));
    }

    @Test void serverCopiesActualClassicRulesAndPreservesTheLegacyOneLifeProfile() {
        var bombs = new BombRules(900,2,2);
        var match = new MatchRules(7000,1,3,5,160,9);
        var movement = new PlayerMovementRules(20,88);
        var rules = ClassicRulesAdapter.publicRules(bombs,match,movement);
        var registry = new ModeRegistry(rules);
        var configuration = registry.resolvePublic(GameMode.CLASSIC);
        assertEquals(1,configuration.effectiveRules().common().initialLives());
        assertEquals(900,configuration.effectiveRules().common().normalFuseMillis());
        assertEquals(88,configuration.effectiveRules().common().initialSpeed());
        assertEquals(9,configuration.effectiveRules().common().speedIncrement());
        assertEquals(2,configuration.effectiveRules().common().initialBombQuota());
        assertEquals(1.0,configuration.effectiveRules().common().itemDropProbability());
        assertDoesNotThrow(() -> registry.validatePublicStart(configuration,2));
        // Preset Shadow vẫn cố định, không nhận fuse/speed/lives từ registry Classic.
        assertEquals(3000,EffectiveRules.ShadowRelay.v1().common().normalFuseMillis());
        assertEquals(3,EffectiveRules.ShadowRelay.v1().common().initialLives());
    }

    @Test void crossValidationRejectsInitialStatsAboveCapsAndInvalidMovementFit() {
        var match = new MatchRules(5000,.75,2,3,144,8,3,99);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,() -> ClassicRulesAdapter.publicRules(new BombRules(3000,1,3),match,new PlayerMovementRules(24,80))),
                () -> assertThrows(IllegalArgumentException.class,() -> ClassicRulesAdapter.publicRules(new BombRules(3000,4,1),match,new PlayerMovementRules(24,80))),
                () -> assertThrows(IllegalArgumentException.class,() -> ClassicRulesAdapter.publicRules(new BombRules(3000,1,1),match,new PlayerMovementRules(24,160))),
                () -> assertThrows(IllegalArgumentException.class,() -> ClassicRulesAdapter.publicRules(new BombRules(3000,1,1),match,new PlayerMovementRules(32,80))));
    }

    @Test void registryRejectsForgedEffectiveRulesEvenIfDtoNumbersAreValid() {
        var registry = registry();
        var changed = ClassicRulesAdapter.publicRules(new BombRules(1200,1,1),
                new MatchRules(5000,.75,5,8,144,8,3,99),new PlayerMovementRules(24,80));
        var forged = new MatchConfiguration(UUID.randomUUID(),GameMode.CLASSIC,"classic-v1",
                MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",new EffectiveRules.Classic(changed));
        rejected(INVALID_CONFIGURATION,() -> registry.validatePublicConfiguration(forged));
        var shadow = new MatchConfiguration(UUID.randomUUID(),GameMode.SHADOW_RELAY,"shadow-relay-v1",
                MatchPreset.SHADOW_RELAY_V1,MatchProfile.PUBLIC,"shadow-relay-arena-v1",EffectiveRules.ShadowRelay.v1());
        assertDoesNotThrow(() -> registry.validatePublicStart(shadow,4));
        rejected(INVALID_CONFIGURATION,() -> registry.validatePublicStart(shadow,3));
    }

    @Test void publicAndInternalStartPathsCannotBeConfused() {
        var registry = registry();
        var publicConfig = registry.resolvePublic(GameMode.CLASSIC);
        for (int count : new int[]{2,3,4}) assertDoesNotThrow(() -> registry.validatePublicStart(publicConfig,count));
        for (int count : new int[]{0,1,5}) rejected(INVALID_CONFIGURATION,() -> registry.validatePublicStart(publicConfig,count));
        var internalRules = ClassicRulesAdapter.capture(new BombRules(1000,1,1),
                new MatchRules(5000,0,5,8,144,8),new PlayerMovementRules(24,80),8,8,40,50,50,15000,29000);
        var internal = registry.resolveInternalClassic(internalRules);
        assertEquals(1,internal.effectiveRules().common().initialLives());
        assertDoesNotThrow(() -> registry.validateInternalStart(internal,1));
        rejected(INVALID_CONFIGURATION,() -> registry.validatePublicStart(internal,2));
        rejected(INVALID_CONFIGURATION,() -> registry.validateInternalStart(publicConfig,2));
        rejected(INVALID_CONFIGURATION,() -> registry.validateInternalStart(internal,0));
    }

    @Test void clientFeatureIsValidatedWithoutTrustingItAsServerAvailability() {
        var registry = registry();
        var config = registry.resolvePublic(GameMode.CLASSIC);
        assertDoesNotThrow(() -> registry.validateClientFeatures(config,Set.of("CLASSIC_V1")));
        rejected(CLIENT_CAPABILITY_MISMATCH,() -> registry.validateClientFeatures(config,Set.of("SHADOW_RELAY_V1")));
        rejected(CLIENT_CAPABILITY_MISMATCH,() -> registry.validateClientFeatures(config,null));
        var shadow=registry.resolvePublic(GameMode.SHADOW_RELAY);
        rejected(CLIENT_CAPABILITY_MISMATCH,()->registry.validateClientFeatures(shadow,Set.of("CLASSIC_V1")));
        assertDoesNotThrow(()->registry.validateClientFeatures(shadow,Set.of("SHADOW_RELAY_V1")));
    }

    @Test void simultaneousRoomsGetIndependentIdsAndNoMutableSharedConfiguration() throws Exception {
        var registry = registry();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0,64).mapToObj(i -> (Callable<MatchConfiguration>)
                    () -> registry.resolvePublic(GameMode.CLASSIC)).toList();
            var results = pool.invokeAll(tasks);
            var ids = new java.util.HashSet<UUID>();
            for (var future : results) {
                var config = future.get();
                assertTrue(ids.add(config.configurationId()));
                registry.validatePublicStart(config,2);
                assertThrows(UnsupportedOperationException.class,() -> config.effectiveRules().toWireFields().put("initialLives",99));
            }
            assertEquals(3,registry.resolvePublic(GameMode.CLASSIC).effectiveRules().common().initialLives());
        }
    }

    @Test void defaultRegistrySnapshotsPropertiesAndKeepsWireVersionsExplicit() {
        String key = "bombit.bomb.fuseMillis";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key,"1234");
            var first = new ModeRegistry();
            System.setProperty(key,"2345");
            assertEquals(1234,first.resolvePublic(GameMode.CLASSIC).effectiveRules().common().normalFuseMillis());
            assertEquals(2345,new ModeRegistry().resolvePublic(GameMode.CLASSIC).effectiveRules().common().normalFuseMillis());
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key,previous);
        }
        assertEquals(2,IntegrationContract.LEGACY_PROTOCOL_VERSION);
        assertEquals(3,IntegrationContract.PROTOCOL_VERSION);
    }
}

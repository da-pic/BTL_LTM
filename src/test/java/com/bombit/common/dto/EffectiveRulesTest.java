package com.bombit.common.dto;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture model: số không hợp lệ không được tồn tại, không cần socket/game thread. */
class EffectiveRulesTest {
    private static final EffectiveRules.Common BASE = EffectiveRules.Common.shadowRelayV1();

    private static MatchConfiguration configuration(GameMode mode, String version, MatchPreset preset,
                                                   MatchProfile profile, String layout, EffectiveRules rules) {
        return new MatchConfiguration(UUID.randomUUID(), mode, version, preset, profile, layout, rules);
    }

    private static EffectiveRules.Common geometry(int width, int height, int tile, int tick, int input, double hitbox) {
        return new EffectiveRules.Common(width, height, tile, tick, input, hitbox, 80, 8, 144,
                3, 99, 3000, 1, 5, 1, 8, .75, 180000, 30000, 29000);
    }

    @Test void rejectInvalidGeometryAndSimulationStepsBeforeAConfigurationIsPublished() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(0,15,32,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,-1,32,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(65536,1,32,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(65,65,32,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,0,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,65536,50,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,0,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,101,50,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,50,0,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,50,101,24)),
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,50,50,32)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, -1})
    void nonFiniteOrNonPositiveMovementNeverEntersTheModel(double invalid) {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> geometry(17,15,32,50,50,invalid)),
                () -> assertThrows(IllegalArgumentException.class, () -> movement(invalid,8,144)),
                () -> assertThrows(IllegalArgumentException.class, () -> movement(80,invalid,144)),
                () -> assertThrows(IllegalArgumentException.class, () -> movement(80,8,invalid)));
    }

    private static EffectiveRules.Common movement(double initial, double increment, double max) {
        return new EffectiveRules.Common(17,15,32,50,50,24,initial,increment,max,
                3,99,3000,1,5,1,8,.75,180000,30000,29000);
    }

    @Test void initialSpeedCannotExceedTheCap() {
        assertThrows(IllegalArgumentException.class, () -> movement(160,8,144));
    }

    @Test void invalidLivesQuotaRangeProbabilityAndTimersAreRejected() {
        // Thay một đầu vào của fixture hợp lệ, không lặp lại biểu thức validation để tính expected.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> changed("initialLives",0)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("maxLives",2)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("initialBombQuota",6)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("maxBombRange",0)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("initialBombRange",9)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("normalFuseMillis",Long.MAX_VALUE)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("itemDropProbability",Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("itemDropProbability",-.01)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("itemDropProbability",1.01)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("durationMillis",86400001L)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("sessionGraceMillis",0)),
                () -> assertThrows(IllegalArgumentException.class, () -> changed("clientRecoveryBudgetMillis",0)));
    }

    private static EffectiveRules.Common changed(String field, Object value) throws Exception {
        var components = EffectiveRules.Common.class.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i=0;i<components.length;i++) {
            types[i]=components[i].getType();
            values[i]=components[i].getName().equals(field) ? value : components[i].getAccessor().invoke(BASE);
        }
        try { return EffectiveRules.Common.class.getDeclaredConstructor(types).newInstance(values); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }

    /** Luật khác nhau nhưng cùng nhãn version không được vượt validation bằng constructor trực tiếp. */
    @Test void rejectMismatchedModePresetVersionProfileLayoutAndRuleType() {
        var classic = new EffectiveRules.Classic(BASE);
        var shadow = EffectiveRules.ShadowRelay.v1();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CLASSIC,"shadow-relay-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",classic)),
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.SHADOW_RELAY,"classic-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",shadow)),
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CLASSIC,"classic-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.INTERNAL,"classic-generator-v1",classic)),
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CLASSIC,"classic-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"FOREST",classic)),
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CLASSIC,"classic-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",shadow)),
                () -> assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CIRCUIT,"classic-v1",
                        MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",classic)));
    }

    @Test void publicRequiresTheReleaseGridButInternalRetainsExplicitFixtureGeometry() {
        var smaller = new EffectiveRules.Classic(geometry(8,8,40,50,50,24));
        assertThrows(IllegalArgumentException.class, () -> configuration(GameMode.CLASSIC,"classic-v1",
                MatchPreset.CLASSIC_SERVER_DEFAULT,MatchProfile.PUBLIC,"classic-generator-v1",smaller));
        assertDoesNotThrow(() -> configuration(GameMode.CLASSIC,"classic-v1",
                MatchPreset.CLASSIC_INTERNAL,MatchProfile.INTERNAL,"classic-generator-v1",smaller));
    }

    @Test void classicOmitsShadowFieldsAndWireViewsAreDeeplyImmutable() {
        var classic = new EffectiveRules.Classic(BASE).toWireFields();
        assertFalse(classic.containsKey("scan"));
        assertFalse(classic.containsKey("teamSize"));
        assertFalse(classic.containsKey("targetScore"));
        var shadow = EffectiveRules.ShadowRelay.v1();
        var first = shadow.toWireFields();
        assertThrows(UnsupportedOperationException.class, () -> first.put("captureTicks",1));
        var scan = (Map<?,?>) first.get("scan");
        assertThrows(UnsupportedOperationException.class, scan::clear);
        assertThrows(UnsupportedOperationException.class, () -> first.entrySet().iterator().next().setValue(0));
        assertEquals(first,shadow.toWireFields());
        assertEquals(Map.of("fuseMillis",1500L,"activeMillis",3000L,"radius",4,"cooldownMillis",8000L),scan);
    }

    @Test void shadowV1CannotBeRetunedWithoutChangingTheContract() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new EffectiveRules.ShadowRelay(BASE,4,2,true,3,1,39,20,60,EffectiveRules.Scan.v1())),
                () -> assertThrows(IllegalArgumentException.class, () -> new EffectiveRules.ShadowRelay(BASE,4,2,false,3,1,40,20,60,EffectiveRules.Scan.v1())),
                () -> assertThrows(IllegalArgumentException.class, () -> new EffectiveRules.ShadowRelay(BASE,4,2,true,3,1,40,20,60,new EffectiveRules.Scan(1000,3000,4,8000))),
                () -> assertThrows(IllegalArgumentException.class, () -> new EffectiveRules.Scan(Long.MAX_VALUE,3000,4,8000)));
    }

    @Test void nullRequiredDataAndInventedCapabilityCombinationsAreRejected() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new EffectiveRules.Classic(null)),
                () -> assertThrows(NullPointerException.class, () -> new MatchConfiguration(null,GameMode.CLASSIC,"classic-v1",MatchPreset.CLASSIC_SERVER_DEFAULT,
                        MatchProfile.PUBLIC,"classic-generator-v1",new EffectiveRules.Classic(BASE))),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeCapabilities(false,false,true,false,false,false)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeCapabilities(false,false,false,false,true,false)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeCapabilities(false,false,false,false,false,true)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeDescriptor(GameMode.SHADOW_RELAY,"shadow-relay-v1",false,
                        ModeDescriptor.UnavailableReason.NOT_IMPLEMENTED,4,4,2,"SHADOW_RELAY_V1",new ModeCapabilities(true,true,true,false,false,false))),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeDescriptor(GameMode.CLASSIC,null,true,null,2,4,0,null,ModeCapabilities.NONE)),
                () -> assertThrows(IllegalArgumentException.class, () -> new ModeDescriptor(GameMode.CLASSIC,"classic-v1",true,
                        ModeDescriptor.UnavailableReason.DISABLED,2,4,0,"CLASSIC_V1",ModeCapabilities.NONE)));
    }
}

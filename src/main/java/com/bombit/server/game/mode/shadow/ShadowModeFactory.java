package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.ModeRuntimeFactory;
import com.bombit.server.game.runtime.GameplayExtension;
import com.bombit.server.game.item.RandomItemSpawnPolicy;
import java.util.*;
import java.util.function.LongSupplier;

/** Factory dùng GameLoop chung; preset Shadow không kế thừa property cân bằng của Classic. */
public final class ShadowModeFactory implements ModeRuntimeFactory {
    private final LongSupplier nanos,epoch;
    public ShadowModeFactory(){this(System::nanoTime,System::currentTimeMillis);}
    public ShadowModeFactory(LongSupplier nanos,LongSupplier epoch){this.nanos=Objects.requireNonNull(nanos);this.epoch=Objects.requireNonNull(epoch);}
    @Override public GameMode gameMode(){return GameMode.SHADOW_RELAY;}
    @Override public LongSupplier nanoClock(){return nanos;}
    @Override public LongSupplier epochClock(){return epoch;}
    private static EffectiveRules.ShadowRelay rules(MatchConfiguration config){
        if(config.gameMode()!=GameMode.SHADOW_RELAY||config.profile()!=MatchProfile.PUBLIC||!(config.effectiveRules() instanceof EffectiveRules.ShadowRelay shadow))throw new IllegalArgumentException("Not Shadow PUBLIC");
        return shadow;
    }
    @Override public GameMap createMap(String room,MatchConfiguration config){rules(config);return ShadowArena.create();}
    @Override public GameplayExtension createGameplay(String room,MatchConfiguration config,GameState state,Map<String,Integer> players){
        var rules=rules(config);
        // Tên hiển thị thuộc metadata lobby; gameplay chỉ cần session/player/đội ổn định.
        var roster=new ShadowRoster(players.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(p->
                new MatchStartMetadata.Member(p.getKey(),p.getValue(),p.getValue(),"P"+p.getValue(),ShadowRoster.teamForSlot(p.getValue()))).toList());
        return new ShadowGameplayExtension(state,roster,rules,new RandomItemSpawnPolicy(rules.common().itemDropProbability(),new Random()),e->{},r->{},nanos,epoch);
    }
}

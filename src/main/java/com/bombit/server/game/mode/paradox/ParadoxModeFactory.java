package com.bombit.server.game.mode.paradox;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.mode.ModeRuntimeFactory;
import com.bombit.server.game.runtime.*;
import java.util.*;

/** Mỗi trận có world, lịch sử và RNG riêng; theme không thay đổi luật rewind. */
public final class ParadoxModeFactory implements ModeRuntimeFactory {
    public GameMode gameMode(){return GameMode.PARADOX;}
    private EffectiveRules.Common rules(MatchConfiguration configuration){
        if(configuration.gameMode()!=GameMode.PARADOX||!(configuration.effectiveRules() instanceof EffectiveRules.Paradox))
            throw new IllegalArgumentException("Wrong Paradox configuration");
        return configuration.effectiveRules().common();
    }
    public GameMap createMap(String roomId,MatchConfiguration configuration){rules(configuration);return ArenaGenerator.create();}
    public GameplayExtension createGameplay(String roomId,MatchConfiguration configuration,GameState state,Map<String,Integer> players){
        var r=rules(configuration);
        var game=new ParadoxGameplayExtension(state,players,new BombRules(r.normalFuseMillis(),r.initialBombRange(),r.initialBombQuota()),
                new MatchRules(r.durationMillis(),r.itemDropProbability(),r.maxBombQuota(),r.maxBombRange(),r.maxSpeed(),r.speedIncrement(),r.initialLives(),r.maxLives()),
                new java.security.SecureRandom().nextLong());
        if(configuration.isTeamMatch())game.useTeams(state.getPlayers().stream().map(Player::getId).toList());
        return game;
    }
}

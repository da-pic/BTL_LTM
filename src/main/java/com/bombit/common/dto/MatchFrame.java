package com.bombit.common.dto;

import java.util.*;

/** Complete HUD state in both full and delta snapshots; speed is already in PlayerData. */
public record MatchFrame(int remainingSeconds,List<Stats> players,MatchResult result,TeamMatchResult teamResult) {
    public MatchFrame(int seconds,List<Stats> players,MatchResult result){this(seconds,players,result,null);}
    public record Stats(int playerId,int bombLimit,int range,int lives,boolean shield,int respawns) {
        public Stats(int playerId,int bombLimit,int range){this(playerId,bombLimit,range,1,false,0);}
        public Stats { if(playerId<=0||bombLimit<=0||range<=0||lives<0||respawns<0)throw new IllegalArgumentException("Invalid stats"); }
    }
    public MatchFrame {
        players=List.copyOf(players);
        if(result!=null&&teamResult!=null||remainingSeconds<0||players.size()>4||players.stream().map(Stats::playerId).distinct().count()!=players.size())
            throw new IllegalArgumentException("Invalid match frame");
    }
}

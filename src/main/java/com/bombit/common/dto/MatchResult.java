package com.bombit.common.dto;

import java.util.*;

/** Final server decision. Equal survival time receives equal rank. */
public record MatchResult(long tick,Integer winnerPlayerId,Reason reason,List<Ranking> rankings) {
    public enum Reason { LAST_ALIVE, ALL_DEAD, TIMEOUT, ABORTED }
    public record Ranking(int playerId,int rank) {
        public Ranking { if(playerId<=0||rank<=0||rank>4)throw new IllegalArgumentException("Invalid ranking"); }
    }
    public MatchResult {
        Objects.requireNonNull(reason);rankings=List.copyOf(rankings);
        if(tick<0||rankings.size()>4||rankings.stream().map(Ranking::playerId).distinct().count()!=rankings.size())
            throw new IllegalArgumentException("Invalid result");
        if((reason==Reason.LAST_ALIVE)!=(winnerPlayerId!=null))throw new IllegalArgumentException("Invalid winner");
        if(winnerPlayerId!=null && rankings.stream().noneMatch(r->r.playerId()==winnerPlayerId&&r.rank()==1))
            throw new IllegalArgumentException("Winner missing from rankings");
    }
}

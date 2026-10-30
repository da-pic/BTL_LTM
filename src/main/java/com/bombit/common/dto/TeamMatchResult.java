package com.bombit.common.dto;

import java.util.Objects;

/** Kết quả đội Classic/Paradox; không giả team ID thành winnerPlayerId. */
public record TeamMatchResult(long tick,TeamId winningTeam,Reason reason) {
    public enum Reason { TEAM_ELIMINATED, BOTH_TEAMS_ELIMINATED, TIMEOUT, ABORTED }
    public TeamMatchResult {
        Objects.requireNonNull(reason);
        if(tick<0||(reason==Reason.TEAM_ELIMINATED)!=(winningTeam!=null))throw new IllegalArgumentException("Invalid team result");
    }
}

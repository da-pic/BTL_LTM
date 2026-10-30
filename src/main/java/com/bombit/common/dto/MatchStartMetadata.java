package com.bombit.common.dto;

import java.util.*;

/** Context v3 đầy đủ, bao identity cũ; không đoán rule từ constructor MatchContext legacy. */
public record MatchStartMetadata(MatchContext identity,MatchConfiguration configuration,long configurationRevision,
                                 List<Member> players,StreamContext stream) {
    public record Member(String sessionId,int playerId,int slot,String username,TeamId team) {
        public Member {Objects.requireNonNull(sessionId);Objects.requireNonNull(username);
            if(playerId<=0||slot<1||slot>4||username.isBlank()||username.codePointCount(0,username.length())>32
                    ||username.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid match member");}
    }
    public MatchStartMetadata {
        Objects.requireNonNull(identity);Objects.requireNonNull(configuration);Objects.requireNonNull(stream);players=List.copyOf(players);
        if(configurationRevision<1||configuration.profile()!=MatchProfile.PUBLIC||players.size()<2||players.size()>4
                ||players.size()!=identity.players().size())throw new IllegalArgumentException("Invalid match metadata");
        for(int i=0;i<players.size();i++){var member=players.get(i);var old=identity.players().get(i);
            if(member.slot()!=i+1||member.playerId()!=old.playerId()||!member.sessionId().equals(old.sessionId()))throw new IllegalArgumentException("Mapping mismatch");
            TeamId expected=configuration.teamForSlot(i+1);
            if(member.team()!=expected)throw new IllegalArgumentException("Team mismatch");}
        if(configuration.isTeamMatch()&&players.size()!=4)throw new IllegalArgumentException("Team match needs four players");
        if(players.stream().noneMatch(p->p.playerId()==stream.recipientPlayerId()))throw new IllegalArgumentException("Recipient absent");
        if(configuration.gameMode()!=GameMode.PARADOX&&stream.timelineEpoch()!=0)throw new IllegalArgumentException("Timeline unsupported by mode");
    }
    public MatchStartMetadata withStream(StreamContext next){return new MatchStartMetadata(identity,configuration,configurationRevision,players,next);}
}

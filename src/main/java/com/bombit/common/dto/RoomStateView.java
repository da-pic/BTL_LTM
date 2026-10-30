package com.bombit.common.dto;

import java.util.*;

/** Ảnh lobby v3 immutable; không thay DTO legacy hoặc chứa token/session mutable. */
public record RoomStateView(String roomId,String roomName,String hostId,String state,int maxPlayers,
                            MapSelection mapSelection,long configurationRevision,MatchConfiguration configuration,List<Member> players) {
    public record Member(String sessionId,String username,String state,int slot,TeamId team,Long readyRevision) {
        public Member {Objects.requireNonNull(sessionId);Objects.requireNonNull(username);Objects.requireNonNull(state);
            if(slot<1||slot>4||readyRevision!=null&&readyRevision<1)throw new IllegalArgumentException("Invalid room member");}
    }
    public RoomStateView {
        Objects.requireNonNull(roomId);Objects.requireNonNull(roomName);Objects.requireNonNull(hostId);Objects.requireNonNull(state);
        Objects.requireNonNull(mapSelection);Objects.requireNonNull(configuration);players=List.copyOf(players);
        if(configuration.profile()!=MatchProfile.PUBLIC||maxPlayers<2||maxPlayers>4||configurationRevision<1||players.isEmpty()||players.size()>maxPlayers
                ||!Set.of("WAITING","PLAYING").contains(state))throw new IllegalArgumentException("Invalid room view");
        var ids=new HashSet<String>();boolean host=false;int previousSlot=0;
        for(int i=0;i<players.size();i++){var p=players.get(i);if(!ids.add(p.sessionId())||("WAITING".equals(state)?p.slot()!=i+1:p.slot()<=previousSlot))throw new IllegalArgumentException("Invalid roster");
            previousSlot=p.slot();
            if(p.sessionId().equals(hostId))host=true;
            if(p.team()!=configuration.teamForSlot(p.slot())||configuration.isTeamMatch()&&maxPlayers!=4)
                throw new IllegalArgumentException("Match format/team mismatch");
            if(p.state().equals("READY")&&p.readyRevision()==null)throw new IllegalArgumentException("Missing READY revision");
            if(p.readyRevision()!=null&&p.readyRevision()!=configurationRevision)throw new IllegalArgumentException("Stale READY in view");}
        if(!host)throw new IllegalArgumentException("Missing host");
    }
}

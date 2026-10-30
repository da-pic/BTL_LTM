package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.model.TilePosition;
import java.util.*;

/** Roster đội bất biến; quyết định sau toàn batch damage/item, không xét từng vụ nổ riêng. */
public final class TeamMatchPolicy {
    private final Map<Integer,TeamId> teams;
    public TeamMatchPolicy(List<Integer> slots){
        if(slots.size()!=4||new HashSet<>(slots).size()!=4||slots.stream().anyMatch(id->id==null||id<=0))
            throw new IllegalArgumentException("Team match needs four distinct players");
        var mapping=new LinkedHashMap<Integer,TeamId>();
        for(int i=0;i<4;i++)mapping.put(slots.get(i),MatchFormat.TEAM_2V2.teamForSlot(i+1));
        teams=Map.copyOf(mapping);
    }
    public Map<Integer,TeamId> teams(){return teams;}
    /** A ở hai góc trái, B ở hai góc phải; dùng các ô an toàn của generator Classic. */
    public static TilePosition spawnForSlot(int slot){
        MatchFormat.TEAM_2V2.teamForSlot(slot);return ArenaGenerator.SPAWNS.get(slot-1);
    }
    public TeamMatchResult decide(GameState state,long tick,boolean expired){
        boolean a=false,b=false;
        for(var entry:teams.entrySet()){
            var player=state.getPlayer(entry.getKey());
            // Frozen vẫn sống và chịu damage; identity đã gỡ khỏi world được coi là bị loại.
            if(player!=null&&player.isAlive()){if(entry.getValue()==TeamId.A)a=true;else b=true;}
        }
        if(!a&&!b)return new TeamMatchResult(tick,null,TeamMatchResult.Reason.BOTH_TEAMS_ELIMINATED);
        if(!a||!b)return new TeamMatchResult(tick,a?TeamId.A:TeamId.B,TeamMatchResult.Reason.TEAM_ELIMINATED);
        return expired?new TeamMatchResult(tick,null,TeamMatchResult.Reason.TIMEOUT):null;
    }
}

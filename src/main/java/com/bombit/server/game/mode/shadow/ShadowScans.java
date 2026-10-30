package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.BombExtras;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.EntityData;
import java.util.*;
import java.util.function.*;

/** Timer/quota của scan chỉ chạy trên game thread, không scheduler và không gây damage. */
final class ShadowScans implements BombExtras {
    private record Pending(int id,int owner,TeamId team,TilePosition cell,long due) { }
    private record Active(int id,int owner,TeamId team,TilePosition cell,long expires) { }
    private final GameState state;
    private final ShadowRoster roster;
    private final EffectiveRules.Scan rules;
    private final LongSupplier clock;
    private final IntSupplier ids;
    private final Map<Integer,Pending> pending=new LinkedHashMap<>();
    private final Map<Integer,Active> active=new LinkedHashMap<>();
    private final Map<Integer,Long> cooldown=new HashMap<>();
    private long started,publishedTime;
    ShadowScans(GameState state,ShadowRoster roster,EffectiveRules.Scan rules,LongSupplier clock,IntSupplier ids) {
        this.state=state;this.roster=roster;this.rules=rules;this.clock=clock;this.ids=ids;started=clock.getAsLong();
    }
    void start(long origin){started=origin;publishedTime=0;}
    private long now(){return Math.max(0,(clock.getAsLong()-started)/1_000_000);}
    PlantRequestResult plant(PlantBombCommand request,boolean frozen){
        int owner=request.getPlayerId();var player=state.getPlayer(owner);String error=null;long now=now();
        if(state.getMatchState()!=GameState.MatchState.RUNNING)error="MATCH_NOT_RUNNING";
        else if(player==null)error="PLAYER_NOT_FOUND";
        else if(!player.isAlive())error="PLAYER_NOT_ALIVE";
        else if(frozen)error="PLAYER_DISCONNECTED";
        else if(cooldown.getOrDefault(owner,0L)>now)error="SCAN_COOLDOWN";
        if(error!=null)return rejected(request,error);
        var cell=player.tilePosition(state.getMap());
        var bombs=state.getEntities().stream().filter(e->e.kind()==EntityData.Kind.BOMB||e.kind()==EntityData.Kind.SCAN_PENDING).toList();
        String session=roster.player(owner).sessionId();
        if(state.getMap().tileAt(cell.x(),cell.y())!=TileType.EMPTY)return rejected(request,"INVALID_CELL");
        if(bombs.stream().anyMatch(e->e.tileX()==cell.x()&&e.tileY()==cell.y()))return rejected(request,"CELL_OCCUPIED");
        if(bombs.stream().filter(e->e.ownerId().equals(session)).count()>=player.getBombLimit())return rejected(request,"BOMB_LIMIT_REACHED");
        int id=ids.getAsInt();long due=now+rules.fuseMillis();
        state.putEntity(new EntityData(EntityData.Kind.SCAN_PENDING,id,cell.x(),cell.y(),session,0,due));
        pending.put(id,new Pending(id,owner,roster.teamOf(owner),cell,due));cooldown.put(owner,now+rules.cooldownMillis());
        return new PlantRequestResult(owner,request.requestId(),BombType.SCAN,true,null,id);
    }
    private static PlantRequestResult rejected(PlantBombCommand c,String error){return new PlantRequestResult(c.getPlayerId(),c.requestId(),BombType.SCAN,false,error,null);}
    @Override public int activeCount(String session){return (int)pending.values().stream().filter(p->roster.player(p.owner()).sessionId().equals(session)).count();}
    @Override public boolean occupies(TilePosition cell){return pending.values().stream().anyMatch(p->p.cell().equals(cell));}
    @Override public void hitByBlast(TilePosition cell){for(var p:List.copyOf(pending.values()))if(p.cell().equals(cell))activate(p,now());}
    private void activate(Pending p,long now){
        pending.remove(p.id());state.removeEntity("SCAN_PENDING:"+p.id());
        active.put(p.id(),new Active(p.id(),p.owner(),p.team(),p.cell(),now+rules.activeMillis()));
        state.putEntity(new EntityData(EntityData.Kind.SCAN_BEACON,p.id(),p.cell().x(),p.cell().y(),roster.player(p.owner()).sessionId(),0,now+rules.activeMillis()));
    }
    void update(){long now=now();publishedTime=now;for(var p:List.copyOf(pending.values()))if(now>=p.due())activate(p,now);active.values().removeIf(s->{if(now<s.expires())return false;state.removeEntity("SCAN_BEACON:"+s.id());return true;});}
    // Giữ timestamp của lần xử lý timer: CPU tạo frame không được làm deadline hết hạn trước state đã xử lý.
    ShadowScanFrame snapshot(){return new ShadowScanFrame(publishedTime,pending.values().stream().map(p->new ShadowScanFrame.Pending(p.id(),p.owner(),p.team(),p.cell().x(),p.cell().y(),p.due())).toList(),
            active.values().stream().map(p->new ShadowScanFrame.Active(p.id(),p.owner(),p.team(),p.cell().x(),p.cell().y(),p.expires())).toList(),cooldown);}
    void clear(){for(int id:pending.keySet())state.removeEntity("SCAN_PENDING:"+id);for(int id:active.keySet())state.removeEntity("SCAN_BEACON:"+id);pending.clear();active.clear();cooldown.clear();}
}

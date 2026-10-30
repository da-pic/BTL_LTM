package com.bombit.server.game.replication;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.mode.shadow.ShadowRoster;
import com.bombit.server.game.movement.*;
import java.util.*;

/** Baseline chỉ giữ view đã lọc của một recipient; commit sau enqueue, reset luôn bắt đầu bằng full. */
public final class ShadowRecipientStream implements RecipientStream {
    private MatchStartMetadata metadata;
    private final ShadowRoster roster;
    private ShadowRecipientView baseline,candidate;
    private ShadowSnapshotV6 pending;
    private long barrier=-1;
    private boolean closed;
    public ShadowRecipientStream(MatchStartMetadata metadata){
        if(metadata.configuration().gameMode()!=GameMode.SHADOW_RELAY)throw new IllegalArgumentException("Not Shadow");
        this.metadata=metadata;roster=new ShadowRoster(metadata.players());
    }
    public synchronized MatchStartMetadata metadata(){return metadata;}
    public synchronized boolean active(){return !closed&&baseline!=null;}
    public synchronized long barrier(){return barrier;}
    public synchronized void reset(long tick){
        if(closed)throw new IllegalStateException("Stream closed");var old=metadata.stream();
        metadata=metadata.withStream(new StreamContext(old.recipientPlayerId(),Math.addExact(old.viewEpoch(),1),0,0));
        baseline=null;candidate=null;pending=null;barrier=tick;
    }
    public synchronized ShadowSnapshotV6 build(WorldFrame frame){
        if(closed||frame.snapshot().getTick()<=barrier||baseline!=null&&frame.snapshot().getTick()<=baseline.snapshot().getTick())return null;
        var view=ShadowViewProjector.project(frame,roster,metadata.stream().recipientPlayerId());var current=view.snapshot();
        var update=current;
        if(baseline!=null&&current.getTick()%100!=0&&current.getMatchState()!=GameState.MatchState.FINISHED){
            var previous=baseline.snapshot();var tiles=new ArrayList<GameStateUpdate.TileChange>();
            for(int i=0;i<current.mapTiles().size();i++)if(current.mapTiles().get(i)!=previous.mapTiles().get(i))
                tiles.add(new GameStateUpdate.TileChange(i%current.getMapWidth(),i/current.getMapWidth(),current.mapTiles().get(i)));
            var ids=new HashSet<Integer>();current.getPlayers().forEach(p->ids.add(p.getId()));
            var removed=previous.getPlayers().stream().map(GameStateUpdate.PlayerData::getId).filter(id->!ids.contains(id)).toList();
            var entities=new HashMap<String,EntityData>();previous.getEntityChanges().forEach(e->entities.put(e.key(),e));
            var nextKeys=new HashSet<String>();var changes=new ArrayList<EntityData>();
            for(var e:current.getEntityChanges()){nextKeys.add(e.key());if(!e.equals(entities.get(e.key())))changes.add(e);}
            var removedEntities=entities.keySet().stream().filter(key->!nextKeys.contains(key)).sorted().toList();
            update=GameStateUpdate.delta(current.getTick(),previous.getTick(),current.getMapWidth(),current.getMapHeight(),current.getTileSize(),current.getMatchState(),true,tiles,current.getPlayers(),removed)
                    .withEntities(changes,removedEntities).withBombFrame(current.getBombFrame()).withMatchFrame(current.getMatchFrame()).withGameNotices(current.getGameNotices());
        }
        var publicPlayers=view.publicPlayers().stream().map(p->new ClassicSnapshotV6.PublicPlayer(p.playerId(),p.forfeited()?2:p.alive()?0:1,p.forfeited()?2:p.frozen()?1:0)).toList();
        var own=view.publicPlayers().stream().filter(p->p.playerId()==view.recipientPlayerId()).findFirst().orElseThrow();
        pending=new ShadowSnapshotV6(update,current.getTick(),frame.matchTimeMillis(),publicPlayers,view.lastProcessedInput(),
                own.alive()&&!own.frozen()&&!own.forfeited()&&current.getMatchState()==GameState.MatchState.RUNNING,
                view.objectives(),view.visibleCells(),view.scanReadyAtMillis());
        candidate=view;return pending;
    }
    public synchronized void commit(ShadowSnapshotV6 update){if(closed||pending==null||pending!=update)throw new IllegalStateException("Wrong candidate");baseline=candidate;pending=null;candidate=null;}
    @Override public void commit(RecipientSnapshotV6 update){if(!(update instanceof ShadowSnapshotV6 shadow))throw new IllegalArgumentException("Wrong mode");commit(shadow);}
    public synchronized void close(){closed=true;baseline=null;candidate=null;pending=null;}
}

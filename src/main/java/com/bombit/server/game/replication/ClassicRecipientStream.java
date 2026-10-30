package com.bombit.server.game.replication;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.movement.*;
import java.util.*;

/** M0 Classic: baseline riêng recipient, commit chỉ sau writer enqueue thành công. */
public final class ClassicRecipientStream implements RecipientStream {
    private MatchStartMetadata metadata;
    private WorldFrame baseline;
    private long barrier=-1;
    private boolean closed;
    private ClassicSnapshotV6 pending;
    private WorldFrame candidate;
    public ClassicRecipientStream(MatchStartMetadata metadata){this.metadata=Objects.requireNonNull(metadata);}
    public synchronized MatchStartMetadata metadata(){return metadata;}
    public synchronized boolean active(){return !closed&&baseline!=null;}
    public synchronized long barrier(){return barrier;}
    public synchronized void reset(long barrierTick){
        if(closed)throw new IllegalStateException("Stream closed");
        var old=metadata.stream();if(old.viewEpoch()==Long.MAX_VALUE)throw new IllegalStateException("Epoch exhausted");
        metadata=metadata.withStream(new StreamContext(old.recipientPlayerId(),old.viewEpoch()+1,old.timelineEpoch(),old.authorityEpoch()));baseline=null;pending=null;candidate=null;barrier=barrierTick;
    }
    public synchronized ClassicSnapshotV6 build(WorldFrame frame){
        if(closed||frame.snapshot().getTick()<=barrier||baseline!=null&&frame.snapshot().getTick()<=baseline.snapshot().getTick())return null;
        var current=frame.snapshot();boolean full=baseline==null||current.getTick()%100==0||current.getMatchState()==GameState.MatchState.FINISHED;
        GameStateUpdate update=current;
        if(!full){
            var previous=baseline.snapshot();var tiles=new ArrayList<GameStateUpdate.TileChange>();
            for(int i=0;i<current.mapTiles().size();i++)if(current.mapTiles().get(i)!=previous.mapTiles().get(i))tiles.add(new GameStateUpdate.TileChange(i%current.getMapWidth(),i/current.getMapWidth(),current.mapTiles().get(i)));
            var ids=current.getPlayers().stream().map(GameStateUpdate.PlayerData::getId).collect(java.util.stream.Collectors.toSet());
            var removed=previous.getPlayers().stream().map(GameStateUpdate.PlayerData::getId).filter(id->!ids.contains(id)).toList();
            var oldEntities=new HashMap<String,EntityData>();for(var e:previous.getEntityChanges())oldEntities.put(e.key(),e);
            var newKeys=new HashSet<String>();var entities=new ArrayList<EntityData>();
            for(var e:current.getEntityChanges()){newKeys.add(e.key());if(!e.equals(oldEntities.get(e.key())))entities.add(e);}
            var removedEntities=oldEntities.keySet().stream().filter(key->!newKeys.contains(key)).sorted().toList();
            // M0 gửi nguyên record mọi detail Classic (tối đa 4); chưa tối ưu từng private field.
            update=GameStateUpdate.delta(current.getTick(),previous.getTick(),current.getMapWidth(),current.getMapHeight(),current.getTileSize(),current.getMatchState(),true,tiles,current.getPlayers(),removed)
                    .withEntities(entities,removedEntities).withBombFrame(current.getBombFrame()).withMatchFrame(current.getMatchFrame());
        }
        var byId=new HashMap<Integer,GameStateUpdate.PlayerData>();for(var p:current.getPlayers())byId.put(p.getId(),p);
        var roster=metadata.players().stream().map(p->{var data=byId.get(p.playerId());return new ClassicSnapshotV6.PublicPlayer(p.playerId(),data==null?2:data.isAlive()?0:1,data==null?2:frame.frozen().contains(p.playerId())?1:0);}).toList();
        var local=byId.get(metadata.stream().recipientPlayerId());long ack=local==null?0:local.getLastProcessedInput();
        pending=new ClassicSnapshotV6(update,current.getTick(),frame.matchTimeMillis(),roster,ack,local!=null&&local.isAlive()&&!frame.frozen().contains(local.getId())&&current.getMatchState()==GameState.MatchState.RUNNING);
        candidate=frame;return pending;
    }
    public synchronized void commit(ClassicSnapshotV6 update){if(closed||pending!=update)throw new IllegalStateException("Wrong candidate");baseline=candidate;pending=null;candidate=null;}
    @Override public void commit(RecipientSnapshotV6 update){if(!(update instanceof ClassicSnapshotV6 classic))throw new IllegalArgumentException("Wrong mode");commit(classic);}
    public synchronized void close(){closed=true;baseline=null;pending=null;candidate=null;}
}

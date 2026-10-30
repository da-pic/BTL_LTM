package com.bombit.client.game.movement;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.*;
import java.io.IOException;
import java.util.*;

/** Replica UI-owner: kiểm candidate trước, rồi commit world/mode/self cùng một update hợp lệ. */
public final class ShadowReplica {
    private final ClientWorldState world;
    private MatchStartMetadata metadata;
    private ShadowFrame objectives;
    private Set<TilePosition> visible=Set.of();
    private List<ClassicSnapshotV6.PublicPlayer> publicPlayers=List.of();
    private long time=-1,cooldown;
    private boolean inputEnabled;
    public ShadowReplica(MatchStartMetadata metadata){this(new ClientWorldState(),metadata);}
    public ShadowReplica(ClientWorldState world,MatchStartMetadata metadata){this.world=Objects.requireNonNull(world);bind(metadata);}
    public void bind(MatchStartMetadata next){
        if(next.configuration().gameMode()!=GameMode.SHADOW_RELAY)throw new IllegalArgumentException("Not Shadow");
        if(metadata!=null&&(!metadata.identity().equals(next.identity())||!metadata.configuration().equals(next.configuration())
                ||!metadata.players().equals(next.players())||metadata.configurationRevision()!=next.configurationRevision()||metadata.stream().recipientPlayerId()!=next.stream().recipientPlayerId()
                ||next.stream().viewEpoch()<=metadata.stream().viewEpoch()))throw new IllegalArgumentException("Changed/stale context");
        metadata=next;world.requireFullState();visible=Set.of();time=-1;inputEnabled=false;
    }
    public ClientWorldState.Result accept(Packet outer)throws IOException{
        Packet body;try{body=MatchEnvelopeV3Codec.unwrapUpdate(outer,metadata.identity().matchId(),metadata.stream());}
        catch(ProtocolRejection stale){return ClientWorldState.Result.STALE;}
        return apply(SnapshotV6Codec.decodeShadow(body,metadata));
    }
    public ClientWorldState.Result apply(ShadowSnapshotV6 frame){
        var update=frame.update();if(update.getTick()<=world.getTick())return ClientWorldState.Result.STALE;
        if(frame.matchTimeMillis()<time||!update.isFullState()&&world.needsFullState())return reject();
        var details=update.isFullState()?update.getPlayers():update.getPlayerChanges();
        var expected=new HashSet<Integer>();details.forEach(p->expected.add(p.getId()));
        var candidatePlayers=new HashSet<Integer>();if(!update.isFullState())candidatePlayers.addAll(world.getPlayers().keySet());
        candidatePlayers.removeAll(update.getRemovedPlayerIds());candidatePlayers.addAll(expected);
        // Mỗi update mang đủ record của tập private hiện tại: thiếu removal hide phải resync, không giữ vị trí cũ.
        if(!candidatePlayers.equals(expected)||!expected.equals(update.getMatchFrame().players().stream().map(MatchFrame.Stats::playerId).collect(java.util.stream.Collectors.toSet())))return reject();
        TeamId team=metadata.players().stream().filter(p->p.playerId()==metadata.stream().recipientPlayerId()).findFirst().orElseThrow().team();
        var roster=new HashMap<Integer,ClassicSnapshotV6.PublicPlayer>();frame.publicPlayers().forEach(p->roster.put(p.playerId(),p));
        if(roster.size()!=4)return reject();
        for(var member:metadata.players())if(!roster.containsKey(member.playerId())||member.team()==team&&roster.get(member.playerId()).lifeState()!=2&&!expected.contains(member.playerId()))return reject();
        for(var p:details){var member=metadata.players().stream().filter(m->m.playerId()==p.getId()).findFirst().orElse(null);
            if(member==null||member.team()!=team&&!frame.visibleCells().contains(TilePosition.containing(p.getX()+p.getWidth()/2,p.getY()+p.getHeight()/2,update.getTileSize())))return reject();}
        var entities=new HashMap<String,EntityData>();if(!update.isFullState())entities.putAll(world.getEntities());
        update.getRemovedEntityKeys().forEach(entities::remove);update.getEntityChanges().forEach(e->entities.put(e.key(),e));
        if(entities.size()>1024)return reject();var bombIds=new HashSet<Integer>();
        for(var e:entities.values())if(e.kind()!=EntityData.Kind.ITEM&&!bombIds.add(e.id()))return reject();
        for(var e:entities.values())if(e.kind()==EntityData.Kind.BOMB){
            var owner=metadata.players().stream().filter(p->p.sessionId().equals(e.ownerId())).findFirst().orElse(null);
            if(owner==null||owner.team()!=team&&!frame.visibleCells().contains(new TilePosition(e.tileX(),e.tileY())))return reject();
        }
        var applied=world.apply(update);if(applied!=ClientWorldState.Result.APPLIED)return applied;
        objectives=frame.objectives();visible=frame.visibleCells();publicPlayers=frame.publicPlayers();time=frame.matchTimeMillis();cooldown=frame.scanReadyAtMillis();inputEnabled=frame.inputEnabled();return applied;
    }
    private ClientWorldState.Result reject(){world.requireFullState();inputEnabled=false;return ClientWorldState.Result.NEED_FULL_STATE;}
    public ClientWorldState world(){return world;}
    public ShadowFrame objectives(){return objectives;}
    public Set<TilePosition> visibleCells(){return visible;}
    public List<ClassicSnapshotV6.PublicPlayer> publicPlayers(){return publicPlayers;}
    public long scanReadyAtMillis(){return cooldown;}
    public long matchTimeMillis(){return time;}
    public boolean inputEnabled(){return inputEnabled&&!world.needsFullState();}
}

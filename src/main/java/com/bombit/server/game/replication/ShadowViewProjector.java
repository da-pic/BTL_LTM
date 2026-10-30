package com.bombit.server.game.replication;

import com.bombit.common.dto.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.mode.shadow.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.GameStateUpdate;
import com.bombit.server.game.movement.EntityData;
import java.util.*;

/** Worker chỉ đọc WorldFrame bất biến; lọc cả vị trí, stats, ACK, cooldown và actor trong notice. */
public final class ShadowViewProjector {
    private ShadowViewProjector() { }
    public static ShadowRecipientView project(WorldFrame frame,ShadowRoster roster,int recipient){
        if(frame.shadow()==null||frame.scans()==null)throw new IllegalArgumentException("Not a complete Shadow frame");
        TeamId team=roster.teamOf(recipient);var full=frame.snapshot();
        var players=new HashMap<Integer,GameStateUpdate.PlayerData>();for(var p:full.getPlayers())players.put(p.getId(),p);
        var sources=new ArrayList<TilePosition>();
        for(var p:full.getPlayers())if(roster.teamOf(p.getId())==team&&p.isAlive()&&!frame.frozen().contains(p.getId()))
            sources.add(TilePosition.containing(p.getX()+p.getWidth()/2,p.getY()+p.getHeight()/2,full.getTileSize()));
        var scanSources=frame.scans().active().stream().filter(s->s.team()==team&&s.expiresAtMillis()>frame.scans().matchTimeMillis())
                .map(s->new TilePosition(s.tileX(),s.tileY())).toList();
        var cells=ShadowVisibility.cells(full,sources,scanSources);
        var details=full.getPlayers().stream().filter(p->roster.teamOf(p.getId())==team||cells.contains(
                TilePosition.containing(p.getX()+p.getWidth()/2,p.getY()+p.getHeight()/2,full.getTileSize())))
                // ACK chỉ ở self block bên ngoài; kể cả đồng đội thấy nhau cũng không được nhận sequence của nhau.
                .map(p->new GameStateUpdate.PlayerData(p.getId(),p.getX(),p.getY(),p.getWidth(),p.getHeight(),p.getSpeed(),p.direction(),p.isMoving(),p.isAlive(),0)).toList();
        var allowed=new HashSet<Integer>();for(var p:details)allowed.add(p.getId());
        var stats=full.getMatchFrame().players().stream().filter(s->allowed.contains(s.playerId())).toList();
        // Whitelist: bỏ pickup/death gắn actor, tránh lộ vị trí/chỉ số của đối thủ ngoài view qua event.
        // Luật mới: bom sát thương địch ẩn theo ô đặt; chỉ vụ nổ là công khai. Scan/item giữ chính sách cũ.
        var entities=full.getEntityChanges().stream().filter(e->e.kind()!=EntityData.Kind.BOMB
                ||ownBomb(roster,e.ownerId(),team)||cells.contains(new TilePosition(e.tileX(),e.tileY()))).toList();
        var notices=full.getGameNotices().stream().filter(n->n.event() instanceof BombExplodedEvent||n.event() instanceof ItemSpawnedEvent
                ||n.event() instanceof BombPlacedEvent b&&(ownBomb(roster,b.ownerPlayerId(),team)||cells.contains(b.position()))).toList();
        var filtered=GameStateUpdate.fullStateModel(full.getTick(),full.getMapWidth(),full.getMapHeight(),full.getTileSize(),full.getMatchState(),full.mapTiles(),details)
                .withEntities(entities,List.of()).withBombFrame(full.getBombFrame())
                .withMatchFrame(new MatchFrame(full.getMatchFrame().remainingSeconds(),stats,null)).withGameNotices(notices);
        var publicPlayers=roster.players().stream().map(p->{var data=players.get(p.playerId());return new ShadowRecipientView.PublicPlayer(p.playerId(),p.team(),data!=null&&data.isAlive(),frame.frozen().contains(p.playerId()),data==null);}).toList();
        var scans=new ShadowScanFrame(frame.scans().matchTimeMillis(),frame.scans().pending(),frame.scans().active(),Map.of());
        var own=players.get(recipient);
        return new ShadowRecipientView(recipient,team,filtered,frame.shadow(),scans,cells,publicPlayers,own==null?0:own.getLastProcessedInput(),
                frame.scans().cooldownReadyAtMillis().getOrDefault(recipient,0L));
    }
    private static boolean ownBomb(ShadowRoster roster,String owner,TeamId team){
        return roster.players().stream().anyMatch(p->p.sessionId().equals(owner)&&p.team()==team);
    }
}

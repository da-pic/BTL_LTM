package com.bombit.client.ui.game.bomb;

import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.common.dto.BombFrame;
import com.bombit.common.protocol.BombPacketCodec;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.EntityData;
import java.awt.Graphics2D;
import java.util.*;

/** UI-owned presentation. Only applied snapshots change visuals; notices never replay world effects. */
public final class BombScene {
    private final BombPresentationState state=new BombPresentationState();
    private final BombRenderer bombs=new BombRenderer();
    private final ExplosionRenderer explosions=new ExplosionRenderer();
    private final Set<String> notices=new LinkedHashSet<>();
    private long tick=-1,anchorNanos,anchorMillis;
    private boolean enabled;
    private long noticeCount;

    public void synchronize(ClientWorldState world,BombFrame frame,long localMillis,long nanos) {
        tick=world.getTick();enabled=frame!=null;anchorMillis=localMillis;anchorNanos=nanos;
        long serverNow=frame==null?localMillis:frame.serverTimeMillis();
        state.replaceBombs(world.getEntities().values().stream().filter(e->e.kind()==EntityData.Kind.BOMB)
                .map(e->new BombView(e.id(),new TilePosition(e.tileX(),e.tileY()),
                        localMillis+(e.expiresAtMillis()-serverNow),e.variant())).toList());
        state.replaceExplosions(frame==null?List.of():frame.explosions().stream()
                .filter(e->e.visibleUntilMillis()>serverNow)
                .map(e->new ExplosionView(e.bombId(),e.cells(),localMillis+(e.startedAtMillis()-serverNow),
                        localMillis+(e.visibleUntilMillis()-serverNow))).toList());
    }
    /** Events can arrive before/after snapshots. They are informational and cannot mutate the scene/world. */
    public void notice(BombPacketCodec.Notice notice) {
        if(notice.tick()!=tick) return;
        String key=Long.toString(notice.eventId());
        if(notices.add(key)) {
            noticeCount++;
            if(notices.size()>256) notices.remove(notices.iterator().next());
        }
    }
    public void render(Graphics2D g,int tileSize,long nanos) {
        long now=anchorMillis+(nanos-anchorNanos)/1_000_000L;
        for(var b:state.bombs()) bombs.render(g,b,tileSize,0,0,now);
        renderExplosions(g,tileSize,nanos);
    }
    /** Shadow vẽ bom trực tiếp từ entity công khai; hiệu ứng nổ vẫn dùng cùng timeline presentation. */
    public void renderExplosions(Graphics2D g,int tileSize,long nanos) {
        long now=anchorMillis+(nanos-anchorNanos)/1_000_000L;
        state.removeExpiredExplosions(now);
        for(var e:state.explosions()) explosions.render(g,e,tileSize,0,0,now);
    }
    public BombPresentationState state() { return state; }
    public boolean enabled() { return enabled; }
    public long noticeCount() { return noticeCount; }
    /** Bỏ mọi presentation của nhánh cũ trước khi chờ full timeline mới. */
    public void clear(){state.replaceBombs(List.of());state.replaceExplosions(List.of());notices.clear();noticeCount=0;tick=-1;enabled=false;}
}

package com.bombit.client.ui.game;

import com.bombit.client.game.movement.*;
import com.bombit.common.dto.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.EntityData;
import java.awt.*;
import java.awt.geom.Arc2D;

/** Fog trình bày view đã lọc: bom địch ngoài tầm nhìn đã bị server loại; vụ nổ vẫn công khai. */
public final class ShadowOverlayRenderer {
    private final com.bombit.client.ui.game.bomb.BombRenderer bombs=new com.bombit.client.ui.game.bomb.BombRenderer();
    public void fog(Graphics2D g,ShadowReplica replica,int width,int height,int size){
        g.setColor(new Color(3,8,20,185));
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(!replica.visibleCells().contains(new TilePosition(x,y)))g.fillRect(x*size,y*size,size,size);
    }
    public void objects(Graphics2D g,MovementClientController controller,int size){
        var replica=controller.shadow();if(replica==null)return;
        var frame=replica.objectives();g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,10));
        if(frame!=null)for(var station:frame.stations()){
            int x=station.tileX()*size,y=station.tileY()*size;
            var color=station.contested()?new Color(250,204,21):station.owner()==null?new Color(148,163,184):ShadowHudPanel.teamColor(station.owner());
            g.setColor(new Color(10,15,28,225));g.fillOval(x+1,y+1,size-2,size-2);g.setColor(color);g.setStroke(new BasicStroke(2));g.drawOval(x+2,y+2,size-4,size-4);
            if(station.captureProgress()>0){g.setColor(ShadowHudPanel.teamColor(station.challenger()));g.setStroke(new BasicStroke(4));g.draw(new Arc2D.Double(x+3,y+3,size-6,size-6,90,-360d*station.captureProgress()/40,Arc2D.OPEN));}
            g.setColor(Color.WHITE);g.drawString(station.contested()?"!":station.owner()==null?"·":station.owner().name(),x+size/2-3,y+size/2+4);
            g.setColor(color);String name=switch(station.id()){case 1->"Bắc";case 2->"Giữa";default->"Nam";};g.drawString(name,x+1,y-3);
        }
        for(var entity:controller.world().getEntities().values()){
            // Chỉ vẽ bom được server cấp trong view; không suy diễn vị trí từ thông báo hoặc vụ nổ.
            // Bom đồng đội có thể nằm ngoài vùng nhìn; viền sáng giúp nhận ra trên mọi theme.
            if(entity.kind()==EntityData.Kind.BOMB){
                int x=entity.tileX()*size,y=entity.tileY()*size;
                g.setColor(new Color(255,191,68,65));g.fillOval(x+2,y+2,size-4,size-4);
                g.setColor(new Color(255,211,103));g.setStroke(new BasicStroke(1.5f));g.drawOval(x+2,y+2,size-4,size-4);
                bombs.render(g,new com.bombit.client.ui.game.bomb.BombView(entity.id(),new TilePosition(entity.tileX(),entity.tileY()),entity.expiresAtMillis(),entity.variant()),
                        size,0,0,controller.estimatedMatchMillis());
                continue;
            }
            if(entity.kind()!=EntityData.Kind.SCAN_PENDING&&entity.kind()!=EntityData.Kind.SCAN_BEACON)continue;
            var owner=controller.metadata().players().stream().filter(p->p.sessionId().equals(entity.ownerId())).findFirst().orElseThrow();
            int x=entity.tileX()*size,y=entity.tileY()*size;g.setColor(ShadowHudPanel.teamColor(owner.team()));
            if(entity.kind()==EntityData.Kind.SCAN_PENDING){
                g.fillRoundRect(x+7,y+7,size-14,size-14,7,7);g.setColor(new Color(10,15,28));g.drawString("F",x+size/2-3,y+size/2+4);
            }else{
                g.setStroke(new BasicStroke(2));g.drawOval(x+3,y+3,size-6,size-6);g.drawOval(x+9,y+9,size-18,size-18);
                g.drawLine(x+size/2,y+2,x+size/2,y+size-2);g.drawLine(x+2,y+size/2,x+size-2,y+size/2);
            }
        }
    }
}

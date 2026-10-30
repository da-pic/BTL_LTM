package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.model.TilePosition;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Blob mode/result của binary v6. ID trạm wire 0..2, model server 1..3. */
public final class ShadowModeCodec {
    private ShadowModeCodec() { }
    public static Map<String,Object> mode(ShadowSnapshotV6 update){
        var frame=update.objectives();int width=update.update().getMapWidth();
        return body("type","SHADOW_RELAY","scoreA",frame.scoreA(),"scoreB",frame.scoreB(),
                "stations",frame.stations().stream().map(s->body("id",s.id()-1,"owner",s.owner()==null?null:s.owner().name(),
                        "captureTeam",s.challenger()==null?null:s.challenger().name(),"captureProgress",s.captureProgress(),
                        "scoreProgress",s.scoreProgress(),"contested",s.contested())).toList(),
                "visionTiles",update.visibleCells().stream().map(p->p.y()*width+p.x()).sorted().toList());
    }
    public static Map<String,Object> result(ShadowFrame.Result result){return body("type","TEAM","gameMode","SHADOW_RELAY","tick",result.tick(),
            "winningTeam",result.winningTeam()==null?null:result.winningTeam().name(),"reason",result.reason().name(),"scoreA",result.scoreA(),"scoreB",result.scoreB());}
    public static ShadowFrame.Result result(Object raw){
        var m=fields(raw,"type","gameMode","tick","winningTeam","reason","scoreA","scoreB");
        if(!"TEAM".equals(m.get("type"))||!"SHADOW_RELAY".equals(m.get("gameMode")))throw new IllegalArgumentException("Wrong result mode");
        int a=(int)integer(m,"scoreA",0,Integer.MAX_VALUE),b=(int)integer(m,"scoreB",0,Integer.MAX_VALUE);
        var reason=ShadowFrame.Reason.valueOf(text(m,"reason",32));var winner=team(m.get("winningTeam"));
        if((reason==ShadowFrame.Reason.TARGET_SCORE||reason==ShadowFrame.Reason.TIMEOUT)&&winner!=(a==b?null:a>b?TeamId.A:TeamId.B)
                ||reason==ShadowFrame.Reason.TARGET_SCORE&&Math.max(a,b)<60)throw new IllegalArgumentException("Wrong winning team");
        return new ShadowFrame.Result(integer(m,"tick",0,Long.MAX_VALUE),winner,reason,a,b);
    }
    private static TeamId team(Object value){if(value==null)return null;if(!(value instanceof String s))throw new IllegalArgumentException("Invalid team");return TeamId.valueOf(s);}
    public record Mode(ShadowFrame frame,Set<TilePosition> vision) { }
    public static Mode mode(Object raw,long tick,long time,int width,int height,long duration,ShadowFrame.Result result){
        var m=fields(raw,"type","scoreA","scoreB","stations","visionTiles");if(!"SHADOW_RELAY".equals(m.get("type")))throw new IllegalArgumentException("Wrong mode frame");
        var stations=new ArrayList<ShadowFrame.Station>();var list=list(m.get("stations"),3);if(list.size()!=3)throw new IllegalArgumentException("Station count");
        for(int i=0;i<3;i++){var s=fields(list.get(i),"id","owner","captureTeam","captureProgress","scoreProgress","contested");
            if(integer(s,"id",0,2)!=i)throw new IllegalArgumentException("Station order");
            stations.add(new ShadowFrame.Station(i+1,8,3+i*4,team(s.get("owner")),team(s.get("captureTeam")),
                    (int)integer(s,"captureProgress",0,39),(int)integer(s,"scoreProgress",0,19),bool(s,"contested")));}
        var vision=new HashSet<TilePosition>();long previous=-1;
        for(var v:list(m.get("visionTiles"),width*height)){if(!(v instanceof Long n)||n<=previous||n>=(long)width*height)throw new IllegalArgumentException("Vision index/order");
            previous=n;vision.add(new TilePosition((int)(n%width),(int)(n/width)));}
        return new Mode(new ShadowFrame(result==null?tick:result.tick(),(int)Math.max(0,(duration-time+999)/1000),
                (int)integer(m,"scoreA",0,Integer.MAX_VALUE),(int)integer(m,"scoreB",0,Integer.MAX_VALUE),stations,result),Set.copyOf(vision));
    }
}

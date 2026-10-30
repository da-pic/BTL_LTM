package com.bombit.server.game.mode.shadow;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.GameStateUpdate;
import java.util.*;
import java.util.function.BiFunction;

/** Manhattan + supercover tâm ô: chỉ một ô opaque ở cạnh góc cũng chặn đường nhìn chéo. */
public final class ShadowVisibility {
    private ShadowVisibility() { }
    public static Set<TilePosition> cells(GameMap map,Collection<TilePosition> players,Collection<TilePosition> scans){
        return cells(map.getWidthInTiles(),map.getHeightInTiles(),map::tileAt,players,scans);
    }
    public static Set<TilePosition> cells(GameStateUpdate full,Collection<TilePosition> players,Collection<TilePosition> scans){
        if(!full.isFullState())throw new IllegalArgumentException("Visibility needs a full immutable world");
        return cells(full.getMapWidth(),full.getMapHeight(),(x,y)->full.mapTiles().get(y*full.getMapWidth()+x),players,scans);
    }
    private static Set<TilePosition> cells(int width,int height,BiFunction<Integer,Integer,TileType> tiles,
                                         Collection<TilePosition> players,Collection<TilePosition> scans){
        var result=new HashSet<TilePosition>();
        for(var source:players)add(result,source,3,false,width,height,tiles);
        for(var source:scans)add(result,source,4,true,width,height,tiles);
        return Set.copyOf(result);
    }
    private static void add(Set<TilePosition> result,TilePosition source,int radius,boolean scan,int width,int height,
                            BiFunction<Integer,Integer,TileType> tiles){
        if(source.x()<0||source.y()<0||source.x()>=width||source.y()>=height)throw new IllegalArgumentException("Vision source outside map");
        for(int y=Math.max(0,source.y()-radius);y<=Math.min(height-1,source.y()+radius);y++)
            for(int x=Math.max(0,source.x()-radius);x<=Math.min(width-1,source.x()+radius);x++)
                if(Math.abs(x-source.x())+Math.abs(y-source.y())<=radius&&clear(source,x,y,scan,tiles))result.add(new TilePosition(x,y));
    }
    private static boolean opaque(int x,int y,int targetX,int targetY,boolean scan,BiFunction<Integer,Integer,TileType> tiles){
        if(x==targetX&&y==targetY)return false; // Nhìn thấy bề mặt ô đích, không nhìn xuyên ô này.
        var tile=tiles.apply(x,y);return tile==TileType.HARD_WALL||tile==TileType.OUT_OF_BOUNDS||!scan&&tile==TileType.BREAKABLE_BLOCK;
    }
    private static boolean clear(TilePosition from,int targetX,int targetY,boolean scan,BiFunction<Integer,Integer,TileType> tiles){
        int x=from.x(),y=from.y(),dx=Math.abs(targetX-x),dy=Math.abs(targetY-y);
        int sx=Integer.signum(targetX-x),sy=Integer.signum(targetY-y),ix=0,iy=0;
        while(ix<dx||iy<dy){
            int crossX=(1+2*ix)*dy,crossY=(1+2*iy)*dx;
            if(crossX==crossY){
                if(opaque(x+sx,y,targetX,targetY,scan,tiles)||opaque(x,y+sy,targetX,targetY,scan,tiles))return false;
                x+=sx;y+=sy;ix++;iy++;
            }else if(crossX<crossY){x+=sx;ix++;}else{y+=sy;iy++;}
            if(opaque(x,y,targetX,targetY,scan,tiles))return false;
        }
        return true;
    }
}

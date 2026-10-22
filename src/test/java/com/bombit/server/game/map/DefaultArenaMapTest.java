package com.bombit.server.game.map;

import com.bombit.client.game.movement.TileGridRenderer;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefaultArenaMapTest {
    @Test void arenaIsSymmetricWithPermanentBorderPillarsAndDistributedCrates(){
        var map=DefaultArenaMap.create();int crates=0;
        for(int y=0;y<15;y++)for(int x=0;x<17;x++){
            var tile=map.tileAt(x,y);
            assertEquals(tile,map.tileAt(16-x,y));assertEquals(tile,map.tileAt(x,14-y));
            if(x==0||y==0||x==16||y==14||(x%2==0&&y%2==0))assertEquals(TileType.HARD_WALL,tile);
            if(tile==TileType.BREAKABLE_BLOCK)crates++;
        }
        assertTrue(crates>=32&&crates<=64,"Meaningful crate density, without filling the board");
    }
    @Test void everySpawnHasTwoEscapeArmsAndEveryInitialFloorCellIsReachable(){
        var map=DefaultArenaMap.create();var reached=flood(map,DefaultArenaMap.SPAWNS.getFirst());
        for(var spawn:DefaultArenaMap.SPAWNS){
            assertTrue(reached.contains(spawn));
            int dx=spawn.x()<8?1:-1,dy=spawn.y()<7?1:-1;
            for(int step=0;step<=2;step++){
                assertEquals(TileType.EMPTY,map.tileAt(spawn.x()+dx*step,spawn.y()));
                assertEquals(TileType.EMPTY,map.tileAt(spawn.x(),spawn.y()+dy*step));
            }
        }
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(map.tileAt(x,y)==TileType.EMPTY)
            assertTrue(reached.contains(new TilePosition(x,y)),"No isolated walkable pocket");
    }
    @Test void destroyedCratesAreLocalToOneMatchAndRenderPreviewShowsActualLayout()throws Exception{
        var map=DefaultArenaMap.create();assertTrue(map.destroyBlock(2,3));
        assertEquals(TileType.EMPTY,map.tileAt(2,3));assertEquals(TileType.BREAKABLE_BLOCK,DefaultArenaMap.create().tileAt(2,3));
        map=DefaultArenaMap.create();var tiles=new ArrayList<TileType>();
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)tiles.add(map.tileAt(x,y));
        var image=new BufferedImage(544,512,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try{
            new TileGridRenderer().render(g,17,15,32,tiles);
            Color[] colors={Color.BLUE,new Color(10,140,70),Color.MAGENTA,new Color(230,100,20)};
            for(int n=0;n<4;n++){
                var spawn=new com.bombit.server.game.runtime.PlayerMovementRules(24,80).spawn("preview",n+1,DefaultArenaMap.SPAWNS.get(n),32);
                g.setColor(colors[n]);g.fillOval((int)spawn.x(),(int)spawn.y(),(int)spawn.width(),(int)spawn.height());
                g.setColor(Color.WHITE);g.drawString("P"+(n+1),(int)spawn.x()+3,(int)spawn.y()+16);
            }
            g.setColor(Color.WHITE);g.fillRect(0,480,544,32);g.setColor(Color.BLACK);
            g.drawString("Gray stone: solid   |   Wooden crate: breakable   |   P1-P4: spawn",8,501);
        }finally{g.dispose();}
        assertNotEquals(image.getRGB(16,16),image.getRGB(80,112));
        Files.createDirectories(Path.of("target"));ImageIO.write(image,"png",Path.of("target/default-arena-preview.png").toFile());
    }
    private static Set<TilePosition> flood(GameMap map,TilePosition start){
        var seen=new HashSet<TilePosition>();var queue=new ArrayDeque<TilePosition>();seen.add(start);queue.add(start);
        while(!queue.isEmpty()){
            var current=queue.remove();
            for(var direction:Direction.values()){
                var cell=current.move(direction,1);
                if(map.tileAt(cell.x(),cell.y())==TileType.EMPTY&&seen.add(cell))queue.add(cell);
            }
        }
        return seen;
    }
}

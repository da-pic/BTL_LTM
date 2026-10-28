package com.bombit.server.game.map;

import com.bombit.client.ui.game.TileGridRenderer;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArenaGeneratorTest {
    @Test void seededArenasHaveRandomWallsCratesSafeSpawnsAndConnectedPlayers(){
        var first=ArenaGenerator.create(1234);var second=ArenaGenerator.create(5678);int differences=0;
        for(GameMap map:java.util.List.of(first,second)){
            int hard=0,crates=0;
            for(int y=0;y<ArenaGenerator.HEIGHT;y++)for(int x=0;x<ArenaGenerator.WIDTH;x++){
                TileType tile=map.tileAt(x,y);
                if(x==0||y==0||x==ArenaGenerator.WIDTH-1||y==ArenaGenerator.HEIGHT-1)assertEquals(TileType.HARD_WALL,tile);
                else if(tile==TileType.HARD_WALL)hard++;else if(tile==TileType.BREAKABLE_BLOCK)crates++;
            }
            assertTrue(hard>=18&&hard<=30);assertTrue(crates>=34&&crates<=56);
            Set<TilePosition> reached=flood(map,ArenaGenerator.SPAWNS.getFirst());
            for(TilePosition spawn:ArenaGenerator.SPAWNS){assertTrue(reached.contains(spawn));int dx=spawn.x()<8?1:-1,dy=spawn.y()<7?1:-1;for(int step=0;step<=2;step++){assertEquals(TileType.EMPTY,map.tileAt(spawn.x()+dx*step,spawn.y()));assertEquals(TileType.EMPTY,map.tileAt(spawn.x(),spawn.y()+dy*step));}}
        }
        for(int y=1;y<ArenaGenerator.HEIGHT-1;y++)for(int x=1;x<ArenaGenerator.WIDTH-1;x++)if(first.tileAt(x,y)!=second.tileAt(x,y))differences++;
        assertTrue(differences>=20,"Different seeds should produce visibly different arenas");
    }
    @Test void sameSeedIsRepeatableAndDestroyedCratesRemainLocalToOneMatch(){
        var map=ArenaGenerator.create(99);var copy=ArenaGenerator.create(99);TilePosition crate=null;
        for(int y=1;y<ArenaGenerator.HEIGHT-1&&crate==null;y++)for(int x=1;x<ArenaGenerator.WIDTH-1;x++)if(map.tileAt(x,y)==TileType.BREAKABLE_BLOCK){crate=new TilePosition(x,y);break;}
        assertNotNull(crate);assertTrue(map.destroyBlock(crate.x(),crate.y()));assertEquals(TileType.EMPTY,map.tileAt(crate.x(),crate.y()));assertEquals(TileType.BREAKABLE_BLOCK,copy.tileAt(crate.x(),crate.y()));
    }
    @Test void renderPreviewShowsGeneratedLayout()throws Exception{
        var map=ArenaGenerator.create(2026);var tiles=new ArrayList<TileType>();for(int y=0;y<15;y++)for(int x=0;x<17;x++)tiles.add(map.tileAt(x,y));
        var image=new BufferedImage(544,512,BufferedImage.TYPE_INT_ARGB);var graphics=image.createGraphics();
        try{new TileGridRenderer().render(graphics,17,15,32,tiles);Color[] colors={Color.BLUE,new Color(10,140,70),Color.MAGENTA,new Color(230,100,20)};for(int n=0;n<4;n++){var spawn=new com.bombit.server.game.runtime.PlayerMovementRules(24,80).spawn("preview",n+1,ArenaGenerator.SPAWNS.get(n),32);graphics.setColor(colors[n]);graphics.fillOval((int)spawn.x(),(int)spawn.y(),(int)spawn.width(),(int)spawn.height());} }finally{graphics.dispose();}
        Files.createDirectories(Path.of("target"));ImageIO.write(image,"png",Path.of("target/random-arena-preview.png").toFile());
    }
    private static Set<TilePosition> flood(GameMap map,TilePosition start){Set<TilePosition> seen=new HashSet<>();ArrayDeque<TilePosition> queue=new ArrayDeque<>();seen.add(start);queue.add(start);while(!queue.isEmpty()){TilePosition current=queue.remove();for(Direction direction:Direction.values()){TilePosition cell=current.move(direction,1);if(map.tileAt(cell.x(),cell.y())==TileType.EMPTY&&seen.add(cell))queue.add(cell);}}return seen;}
}

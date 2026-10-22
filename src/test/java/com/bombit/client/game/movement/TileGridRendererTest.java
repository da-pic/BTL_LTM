package com.bombit.client.game.movement;

import com.bombit.server.game.model.TileType;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Collections;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TileGridRendererTest {
    @Test void boardHasVisibleGridAndMarksTheSameCellAsServer()throws Exception{
        var image=new BufferedImage(128,96,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        var player=new com.bombit.server.game.core.Player(1,52,32,24,24,150);
        var map=new com.bombit.server.game.core.GameMap(4,3,32);
        try{
            var renderer=new TileGridRenderer();renderer.render(g,4,3,32,Collections.nCopies(12,TileType.EMPTY));
            assertNotEquals(image.getRGB(31,16),image.getRGB(32,16));
            assertNotEquals(image.getRGB(16,31),image.getRGB(16,32));
            renderer.markPlayerCell(g,player.tilePosition(map),32);
            assertEquals(new com.bombit.server.game.model.TilePosition(2,1),player.tilePosition(map));
            assertNotEquals(image.getRGB(66,34),image.getRGB(34,34));
        }finally{g.dispose();}
        Files.createDirectories(Path.of("target"));ImageIO.write(image,"png",Path.of("target/tile-grid-preview.png").toFile());
    }
}

package com.bombit.client.ui.game.bomb;

import com.bombit.client.ui.game.item.ItemRenderer;
import com.bombit.client.ui.game.item.ItemView;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.item.ItemType;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Java2DRendererTest {
    @Test void fiveBasicItemIconsAreVisibleAndDistinct(){
        var signatures=new java.util.HashSet<Integer>();
        for(var type:ItemType.values()){
            var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            try{new ItemRenderer().render(g,new ItemView(1,type,new TilePosition(0,0)),64,0,0,1000);}
            finally{g.dispose();}
            int[] pixels=image.getRGB(0,0,64,64,null,0,64);
            assertTrue(java.util.Arrays.stream(pixels).anyMatch(p->(p>>>24)!=0));
            signatures.add(java.util.Arrays.hashCode(pixels));
        }
        org.junit.jupiter.api.Assertions.assertEquals(5,signatures.size());
    }
    @Test
    void rendersBombExplosionAndItemToImage() {
        BufferedImage image = new BufferedImage(192, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            new BombRenderer().render(
                    graphics,
                    new BombView(1, new TilePosition(0, 0), 3_000, 2),
                    64,
                    0,
                    0,
                    1_000);
            new ExplosionRenderer().render(
                    graphics,
                    new ExplosionView(2, Set.of(new TilePosition(1, 0)), 1_000, 1_500),
                    64,
                    0,
                    0,
                    1_250);
            new ItemRenderer().render(
                    graphics,
                    new ItemView(3, ItemType.FIRE_UP, new TilePosition(2, 0)),
                    64,
                    0,
                    0,
                    1_000);
        } finally {
            graphics.dispose();
        }

        long paintedPixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth())
                .length;
        boolean hasVisiblePixel = false;
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0) {
                hasVisiblePixel = true;
                break;
            }
        }
        assertTrue(paintedPixels > 0);
        assertTrue(hasVisiblePixel);
    }
}

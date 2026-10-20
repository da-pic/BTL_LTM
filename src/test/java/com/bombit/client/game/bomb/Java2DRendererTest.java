package com.bombit.client.game.bomb;

import com.bombit.client.game.item.ItemRenderer;
import com.bombit.client.game.item.ItemView;
import com.bombit.server.game.bomb.TilePosition;
import com.bombit.server.game.item.ItemType;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Java2DRendererTest {
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

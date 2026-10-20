package com.bombit.client.game.bomb;

import com.bombit.client.game.item.ItemPresentationState;
import com.bombit.client.game.item.ItemRenderer;
import com.bombit.client.game.item.ItemView;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * Lớp phủ Swing trong suốt dùng để vẽ các đối tượng trò chơi của TV3 lên bản đồ.
 * Các callback mạng cập nhật trạng thái hiển thị; EDT của Swing thực hiện việc vẽ.
 */
public final class BombItemEffectsPanel extends JPanel {
    private static final int DEFAULT_FRAME_DELAY_MILLIS = 16;

    private final BombPresentationState bombState;
    private final ItemPresentationState itemState;
    private final BombRenderer bombRenderer;
    private final ExplosionRenderer explosionRenderer;
    private final ItemRenderer itemRenderer;
    private final Timer repaintTimer;
    private int tileSize;
    private int mapOffsetX;
    private int mapOffsetY;

    public BombItemEffectsPanel(
            BombPresentationState bombState,
            ItemPresentationState itemState,
            int tileSize) {
        this.bombState = Objects.requireNonNull(bombState);
        this.itemState = Objects.requireNonNull(itemState);
        this.bombRenderer = new BombRenderer();
        this.explosionRenderer = new ExplosionRenderer();
        this.itemRenderer = new ItemRenderer();
        setTileSize(tileSize);
        setOpaque(false);
        setDoubleBuffered(true);
        repaintTimer = new Timer(DEFAULT_FRAME_DELAY_MILLIS, event -> {
            bombState.removeExpiredExplosions(System.currentTimeMillis());
            repaint();
        });
        repaintTimer.setCoalesce(true);
    }

    public void setTileSize(int tileSize) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("Tile size must be positive");
        }
        this.tileSize = tileSize;
        revalidate();
        repaint();
    }

    public void setMapOffset(int mapOffsetX, int mapOffsetY) {
        this.mapOffsetX = mapOffsetX;
        this.mapOffsetY = mapOffsetY;
        repaint();
    }

    public void setMapSizeInTiles(int columns, int rows) {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Map dimensions must be positive");
        }
        setPreferredSize(new Dimension(
                mapOffsetX + columns * tileSize,
                mapOffsetY + rows * tileSize));
        revalidate();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        repaintTimer.start();
    }

    @Override
    public void removeNotify() {
        repaintTimer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        long nowMillis = System.currentTimeMillis();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            for (ItemView item : itemState.items()) {
                itemRenderer.render(g, item, tileSize, mapOffsetX, mapOffsetY, nowMillis);
            }
            for (BombView bomb : bombState.bombs()) {
                bombRenderer.render(g, bomb, tileSize, mapOffsetX, mapOffsetY, nowMillis);
            }
            for (ExplosionView explosion : bombState.explosions()) {
                explosionRenderer.render(g, explosion, tileSize, mapOffsetX, mapOffsetY, nowMillis);
            }
        } finally {
            g.dispose();
        }
    }
}

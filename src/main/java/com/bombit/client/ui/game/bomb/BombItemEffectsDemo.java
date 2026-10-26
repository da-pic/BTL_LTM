package com.bombit.client.ui.game.bomb;

import com.bombit.client.ui.game.item.ItemPresentationState;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.item.ItemType;

import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bản xem trước Swing độc lập cho phần hiển thị bom, vụ nổ và vật phẩm của TV3. */
public final class BombItemEffectsDemo {
    private static final int COLUMNS = 13;
    private static final int ROWS = 9;
    private static final int TILE_SIZE = 64;
    private static final int WIDTH = COLUMNS * TILE_SIZE;
    private static final int HEIGHT = ROWS * TILE_SIZE;

    private BombItemEffectsDemo() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BombItemEffectsDemo::showWindow);
    }

    private static void showWindow() {
        BombPresentationState bombState = new BombPresentationState();
        ItemPresentationState itemState = new ItemPresentationState();
        BombItemEffectsPanel effectsPanel = new BombItemEffectsPanel(bombState, itemState, TILE_SIZE);
        effectsPanel.setBounds(0, 0, WIDTH, HEIGHT);

        DemoMapPanel mapPanel = new DemoMapPanel();
        mapPanel.setBounds(0, 0, WIDTH, HEIGHT);

        JLayeredPane layers = new JLayeredPane();
        layers.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        layers.add(mapPanel, Integer.valueOf(0));
        layers.add(effectsPanel, Integer.valueOf(100));

        JFrame frame = new JFrame("Bomb It - TV3 Java 2D Preview");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(layers);
        frame.pack();
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        itemState.itemSpawned(1, ItemType.BOMB_UP, new TilePosition(2, 2));
        itemState.itemSpawned(2, ItemType.FIRE_UP, new TilePosition(10, 2));
        itemState.itemSpawned(3, ItemType.SPEED_UP, new TilePosition(2, 6));
        startBombCycle(bombState, itemState);
    }

    private static void startBombCycle(
            BombPresentationState bombState,
            ItemPresentationState itemState) {
        long now = System.currentTimeMillis();
        int bombId = (int) (now & 0x7fffffff);
        TilePosition center = new TilePosition(6, 4);
        bombState.bombPlaced(bombId, center, now + 3_000, 3);

        Timer explodeTimer = new Timer(3_000, event -> {
            long explosionStart = System.currentTimeMillis();
            bombState.bombExploded(bombId, explosionCells(center, 3), explosionStart, 900);
            itemState.itemPickedUp(2);

            Timer restartTimer = new Timer(1_700, restartEvent -> {
                itemState.itemSpawned(2, ItemType.FIRE_UP, new TilePosition(10, 2));
                startBombCycle(bombState, itemState);
            });
            restartTimer.setRepeats(false);
            restartTimer.start();
        });
        explodeTimer.setRepeats(false);
        explodeTimer.start();
    }

    private static Set<TilePosition> explosionCells(TilePosition center, int range) {
        Set<TilePosition> cells = new LinkedHashSet<>();
        cells.add(center);
        for (int distance = 1; distance <= range; distance++) {
            cells.add(new TilePosition(center.x() + distance, center.y()));
            cells.add(new TilePosition(center.x() - distance, center.y()));
            cells.add(new TilePosition(center.x(), center.y() + distance));
            cells.add(new TilePosition(center.x(), center.y() - distance));
        }
        return cells;
    }

    private static final class DemoMapPanel extends JPanel {
        private DemoMapPanel() {
            setOpaque(true);
            setBackground(new Color(24, 32, 42));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int row = 0; row < ROWS; row++) {
                    for (int column = 0; column < COLUMNS; column++) {
                        drawTile(g, column, row);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        private void drawTile(Graphics2D g, int column, int row) {
            int x = column * TILE_SIZE;
            int y = row * TILE_SIZE;
            boolean border = column == 0 || row == 0 || column == COLUMNS - 1 || row == ROWS - 1;
            boolean hardWall = !border && column % 2 == 0 && row % 2 == 0;

            if (border || hardWall) {
                g.setColor(new Color(62, 78, 98));
                g.fillRoundRect(x + 3, y + 3, TILE_SIZE - 6, TILE_SIZE - 6, 12, 12);
                g.setColor(new Color(100, 122, 148));
                g.drawRoundRect(x + 5, y + 5, TILE_SIZE - 10, TILE_SIZE - 10, 10, 10);
            } else {
                Color floor = (column + row) % 2 == 0
                        ? new Color(41, 93, 74)
                        : new Color(36, 84, 68);
                g.setColor(floor);
                g.fillRect(x, y, TILE_SIZE, TILE_SIZE);
                g.setColor(new Color(255, 255, 255, 14));
                g.drawRect(x, y, TILE_SIZE, TILE_SIZE);
            }
        }
    }
}

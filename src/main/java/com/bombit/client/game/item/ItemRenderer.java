package com.bombit.client.game.item;

import com.bombit.server.game.item.ItemType;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Vẽ các vật phẩm BOMB_UP, FIRE_UP và SPEED_UP bằng các hình Java 2D. */
public final class ItemRenderer {
    public void render(
            Graphics2D graphics,
            ItemView item,
            int tileSize,
            int offsetX,
            int offsetY,
            long nowMillis) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double tileX = offsetX + item.position().x() * tileSize;
            double tileY = offsetY + item.position().y() * tileSize;
            double bob = Math.sin((nowMillis + item.itemId() * 71L) / 180.0) * tileSize * 0.035;
            double size = tileSize * 0.68;
            double x = tileX + (tileSize - size) / 2.0;
            double y = tileY + (tileSize - size) / 2.0 + bob;

            Color theme = theme(item.type());
            g.setColor(new Color(0, 0, 0, 75));
            g.fill(new Ellipse2D.Double(x + size * 0.08, y + size * 0.80, size * 0.84, size * 0.22));
            g.setColor(new Color(theme.getRed(), theme.getGreen(), theme.getBlue(), 58));
            g.fill(new Ellipse2D.Double(x - size * 0.12, y - size * 0.12, size * 1.24, size * 1.24));
            g.setColor(new Color(31, 38, 52, 230));
            g.fill(new RoundRectangle2D.Double(x, y, size, size, size * 0.24, size * 0.24));
            g.setColor(theme);
            g.setStroke(new BasicStroke(Math.max(2f, tileSize * 0.045f)));
            g.draw(new RoundRectangle2D.Double(x, y, size, size, size * 0.24, size * 0.24));

            switch (item.type()) {
                case BOMB_UP -> drawBombIcon(g, x, y, size);
                case FIRE_UP -> drawFireIcon(g, x, y, size);
                case SPEED_UP -> drawSpeedIcon(g, x, y, size);
            }
            drawPlus(g, x, y, size, theme);
        } finally {
            g.dispose();
        }
    }

    private void drawBombIcon(Graphics2D g, double x, double y, double size) {
        g.setColor(new Color(10, 14, 20));
        g.fill(new Ellipse2D.Double(x + size * 0.24, y + size * 0.31, size * 0.48, size * 0.48));
        g.setColor(new Color(163, 177, 198));
        g.fill(new Ellipse2D.Double(x + size * 0.34, y + size * 0.38, size * 0.12, size * 0.12));
        g.setColor(new Color(255, 183, 59));
        g.setStroke(new BasicStroke(Math.max(1.5f, (float) size * 0.055f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (x + size * 0.58), (int) (y + size * 0.34),
                (int) (x + size * 0.69), (int) (y + size * 0.20));
    }

    private void drawFireIcon(Graphics2D g, double x, double y, double size) {
        Path2D flame = new Path2D.Double();
        flame.moveTo(x + size * 0.50, y + size * 0.16);
        flame.curveTo(x + size * 0.70, y + size * 0.38, x + size * 0.76, y + size * 0.57,
                x + size * 0.61, y + size * 0.78);
        flame.curveTo(x + size * 0.54, y + size * 0.86, x + size * 0.36, y + size * 0.82,
                x + size * 0.29, y + size * 0.67);
        flame.curveTo(x + size * 0.20, y + size * 0.48, x + size * 0.39, y + size * 0.37,
                x + size * 0.50, y + size * 0.16);
        g.setColor(new Color(255, 92, 22));
        g.fill(flame);
        g.setColor(new Color(255, 224, 80));
        g.fill(new Ellipse2D.Double(x + size * 0.42, y + size * 0.53, size * 0.18, size * 0.22));
    }

    private void drawSpeedIcon(Graphics2D g, double x, double y, double size) {
        Path2D bolt = new Path2D.Double();
        bolt.moveTo(x + size * 0.56, y + size * 0.14);
        bolt.lineTo(x + size * 0.30, y + size * 0.54);
        bolt.lineTo(x + size * 0.47, y + size * 0.54);
        bolt.lineTo(x + size * 0.38, y + size * 0.84);
        bolt.lineTo(x + size * 0.73, y + size * 0.40);
        bolt.lineTo(x + size * 0.54, y + size * 0.40);
        bolt.closePath();
        g.setColor(new Color(91, 224, 255));
        g.fill(bolt);
        g.setColor(new Color(213, 251, 255));
        g.setStroke(new BasicStroke(Math.max(1f, (float) size * 0.035f)));
        g.draw(bolt);
    }

    private void drawPlus(Graphics2D g, double x, double y, double size, Color theme) {
        String text = "+";
        int fontSize = Math.max(9, (int) (size * 0.30));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int textX = (int) (x + size * 0.78 - metrics.stringWidth(text) / 2.0);
        int textY = (int) (y + size * 0.30 + metrics.getAscent() / 2.0);
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(theme.brighter());
        g.drawString(text, textX, textY);
    }

    private Color theme(ItemType type) {
        return switch (type) {
            case BOMB_UP -> new Color(189, 199, 218);
            case FIRE_UP -> new Color(255, 107, 38);
            case SPEED_UP -> new Color(47, 199, 231);
        };
    }
}

package com.bombit.client.game.bomb;

import com.bombit.server.game.model.TilePosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

/** Vẽ hiệu ứng lửa dao động cho từng ô chịu ảnh hưởng do server cung cấp. */
public final class ExplosionRenderer {
    public void render(
            Graphics2D graphics,
            ExplosionView explosion,
            int tileSize,
            int offsetX,
            int offsetY,
            long nowMillis) {
        if (nowMillis >= explosion.visibleUntilMillis()) {
            return;
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double duration = Math.max(1, explosion.visibleUntilMillis() - explosion.startedAtMillis());
            double progress = clamp((nowMillis - explosion.startedAtMillis()) / duration);
            double envelope = Math.sin(progress * Math.PI);
            double pulse = 0.88 + Math.sin(progress * Math.PI * 6) * 0.08;

            for (TilePosition cell : explosion.affectedCells()) {
                drawCell(g, cell, tileSize, offsetX, offsetY, envelope, pulse);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawCell(
            Graphics2D g,
            TilePosition cell,
            int tileSize,
            int offsetX,
            int offsetY,
            double envelope,
            double pulse) {
        double x = offsetX + cell.x() * tileSize;
        double y = offsetY + cell.y() * tileSize;
        double inset = tileSize * (0.04 + (1 - pulse) * 0.5);
        double size = tileSize - inset * 2;
        int alpha = (int) Math.round(110 + envelope * 145);

        g.setColor(new Color(255, 62, 10, Math.min(255, alpha / 2)));
        g.fill(new RoundRectangle2D.Double(x + inset / 2, y + inset / 2,
                tileSize - inset, tileSize - inset, tileSize * 0.35, tileSize * 0.35));

        Point2D center = new Point2D.Double(x + tileSize / 2.0, y + tileSize / 2.0);
        g.setPaint(new RadialGradientPaint(
                center,
                (float) (size * 0.58),
                new float[]{0f, 0.38f, 0.72f, 1f},
                new Color[]{
                        new Color(255, 255, 224, alpha),
                        new Color(255, 221, 56, alpha),
                        new Color(255, 103, 12, alpha),
                        new Color(177, 24, 8, 0)}));
        g.fill(new Ellipse2D.Double(x + inset, y + inset, size, size));

        g.setStroke(new BasicStroke(Math.max(1f, tileSize * 0.035f)));
        g.setColor(new Color(255, 245, 166, Math.min(255, alpha)));
        double core = size * 0.28;
        g.draw(new Ellipse2D.Double(center.getX() - core / 2, center.getY() - core / 2, core, core));
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}

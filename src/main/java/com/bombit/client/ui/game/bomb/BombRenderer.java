package com.bombit.client.ui.game.bomb;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

/** Chỉ dùng Java 2D để vẽ bom, dây cháy chậm chuyển động và thời gian đếm ngược. */
public final class BombRenderer {
    private static final Color BODY_EDGE = new Color(12, 16, 24);
    private static final Color FUSE_DARK = new Color(94, 58, 30);
    private static final Color FUSE_LIGHT = new Color(238, 188, 91);

    public void render(
            Graphics2D graphics,
            BombView bomb,
            int tileSize,
            int offsetX,
            int offsetY,
            long nowMillis) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            enableQuality(g);
            double tileX = offsetX + bomb.position().x() * tileSize;
            double tileY = offsetY + bomb.position().y() * tileSize;
            double diameter = tileSize * 0.66;
            double bodyX = tileX + (tileSize - diameter) / 2.0;
            double bodyY = tileY + tileSize * 0.25;

            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new Ellipse2D.Double(bodyX + diameter * 0.08, bodyY + diameter * 0.76,
                    diameter * 0.88, diameter * 0.28));

            Point2D center = new Point2D.Double(bodyX + diameter * 0.42, bodyY + diameter * 0.35);
            float radius = (float) (diameter * 0.62);
            g.setPaint(new RadialGradientPaint(
                    center,
                    radius,
                    new float[]{0f, 0.55f, 1f},
                    new Color[]{new Color(102, 115, 135), new Color(38, 45, 58), BODY_EDGE}));
            g.fill(new Ellipse2D.Double(bodyX, bodyY, diameter, diameter));
            g.setColor(new Color(4, 7, 12));
            g.setStroke(new BasicStroke(Math.max(1.5f, tileSize * 0.035f)));
            g.draw(new Ellipse2D.Double(bodyX, bodyY, diameter, diameter));

            double capWidth = diameter * 0.34;
            double capHeight = diameter * 0.20;
            double capX = bodyX + (diameter - capWidth) / 2.0;
            double capY = bodyY - capHeight * 0.35;
            g.setColor(new Color(55, 61, 72));
            g.fill(new RoundRectangle2D.Double(capX, capY, capWidth, capHeight,
                    capHeight * 0.35, capHeight * 0.35));
            g.setColor(BODY_EDGE);
            g.draw(new RoundRectangle2D.Double(capX, capY, capWidth, capHeight,
                    capHeight * 0.35, capHeight * 0.35));

            drawFuse(g, bomb, tileSize, capX + capWidth / 2.0, capY, nowMillis);
            drawCountdown(g, bomb, tileSize, bodyX, bodyY, diameter, nowMillis);
        } finally {
            g.dispose();
        }
    }

    private void drawFuse(
            Graphics2D g,
            BombView bomb,
            int tileSize,
            double startX,
            double startY,
            long nowMillis) {
        double curveHeight = tileSize * 0.22;
        double endX = startX + tileSize * 0.18;
        double endY = startY - curveHeight;
        Path2D fuse = new Path2D.Double();
        fuse.moveTo(startX, startY);
        fuse.curveTo(startX - tileSize * 0.02, startY - curveHeight * 0.55,
                endX - tileSize * 0.08, endY + tileSize * 0.02, endX, endY);
        g.setStroke(new BasicStroke(Math.max(2f, tileSize * 0.065f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(FUSE_DARK);
        g.draw(fuse);
        g.setStroke(new BasicStroke(Math.max(1f, tileSize * 0.022f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(FUSE_LIGHT);
        g.draw(fuse);

        double pulse = 0.65 + 0.35 * Math.sin(nowMillis / 65.0);
        double sparkRadius = tileSize * (0.08 + pulse * 0.035);
        g.setColor(new Color(255, 78, 24, 65));
        g.fill(new Ellipse2D.Double(endX - sparkRadius * 1.8, endY - sparkRadius * 1.8,
                sparkRadius * 3.6, sparkRadius * 3.6));
        g.setColor(new Color(255, 78, 20));
        g.fill(new Ellipse2D.Double(endX - sparkRadius, endY - sparkRadius,
                sparkRadius * 2, sparkRadius * 2));
        g.setColor(new Color(255, 238, 112));
        g.fill(new Ellipse2D.Double(endX - sparkRadius * 0.45, endY - sparkRadius * 0.45,
                sparkRadius * 0.9, sparkRadius * 0.9));
    }

    private void drawCountdown(
            Graphics2D g,
            BombView bomb,
            int tileSize,
            double bodyX,
            double bodyY,
            double diameter,
            long nowMillis) {
        long remainingMillis = Math.max(0, bomb.explodeAtMillis() - nowMillis);
        String text = Long.toString(Math.max(1, (remainingMillis + 999) / 1_000));
        int fontSize = Math.max(10, (int) (tileSize * 0.24));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int textX = (int) Math.round(bodyX + (diameter - metrics.stringWidth(text)) / 2.0);
        int textY = (int) Math.round(bodyY + (diameter + metrics.getAscent() - metrics.getDescent()) / 2.0);
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(new Color(245, 247, 250));
        g.drawString(text, textX, textY);
    }

    private void enableQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}

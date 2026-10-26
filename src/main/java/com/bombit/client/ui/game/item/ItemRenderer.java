package com.bombit.client.ui.game.item;

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
                case LIFE_UP -> drawHeartIcon(g,x,y,size);
                case SHIELD -> drawShieldIcon(g,x,y,size);
                default -> {g.setColor(Color.WHITE);g.drawString("?",(int)(x+size*.4),(int)(y+size*.6));}
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
        g.setColor(new Color(210,225,240));
        g.fill(new RoundRectangle2D.Double(x+size*.38,y+size*.14,size*.24,size*.17,size*.05,size*.05));
        g.setColor(new Color(255,159,35));
        g.fill(new RoundRectangle2D.Double(x+size*.27,y+size*.31,size*.46,size*.5,size*.12,size*.12));
        g.setColor(new Color(255,239,130));
        g.fill(new RoundRectangle2D.Double(x+size*.34,y+size*.42,size*.32,size*.23,size*.05,size*.05));
    }

    private void drawSpeedIcon(Graphics2D g, double x, double y, double size) {
        for(int n=0;n<2;n++){
            double sx=x+size*(.17+n*.27),sy=y+size*(.25+n*.23);
            Path2D shoe=new Path2D.Double();shoe.moveTo(sx,sy);shoe.lineTo(sx+size*.18,sy);
            shoe.lineTo(sx+size*.2,sy+size*.15);shoe.lineTo(sx+size*.38,sy+size*.2);
            shoe.lineTo(sx+size*.38,sy+size*.3);shoe.lineTo(sx,sy+size*.3);shoe.closePath();
            g.setColor(new Color(91,224,255));g.fill(shoe);g.setColor(Color.WHITE);g.draw(shoe);
        }
    }

    private void drawHeartIcon(Graphics2D g,double x,double y,double s){
        Path2D p=new Path2D.Double();p.moveTo(x+s*.5,y+s*.8);
        p.curveTo(x+s*.02,y+s*.48,x+s*.2,y+s*.12,x+s*.5,y+s*.35);
        p.curveTo(x+s*.8,y+s*.12,x+s*.98,y+s*.48,x+s*.5,y+s*.8);
        g.setColor(new Color(255,85,123));g.fill(p);
    }
    private void drawShieldIcon(Graphics2D g,double x,double y,double s){
        Path2D p=new Path2D.Double();p.moveTo(x+s*.23,y+s*.25);p.lineTo(x+s*.5,y+s*.16);
        p.lineTo(x+s*.77,y+s*.25);p.curveTo(x+s*.77,y+s*.62,x+s*.65,y+s*.74,x+s*.5,y+s*.84);
        p.curveTo(x+s*.35,y+s*.74,x+s*.23,y+s*.62,x+s*.23,y+s*.25);
        g.setColor(new Color(115,165,255));g.fill(p);g.setColor(Color.WHITE);g.draw(p);
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
            case LIFE_UP -> new Color(255,85,123);
            case SHIELD -> new Color(115,165,255);
            default -> Color.LIGHT_GRAY;
        };
    }
}

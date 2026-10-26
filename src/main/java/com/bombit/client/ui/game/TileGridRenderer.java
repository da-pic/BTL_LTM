package com.bombit.client.ui.game;

import com.bombit.common.dto.MapTheme;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;

/** Draws the same gameplay tiles with different visual themes. */
public final class TileGridRenderer {
    public void render(Graphics2D graphics,int width,int height,int size,List<TileType> tiles){render(graphics,width,height,size,tiles,MapTheme.CLASSIC);}
    public void render(Graphics2D graphics,int width,int height,int size,List<TileType> tiles,MapTheme theme){
        Graphics2D g=(Graphics2D)graphics.create();try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){TileType tile=tiles.get(y*width+x);drawFloor(g,x,y,size,theme);if(tile==TileType.HARD_WALL||tile==TileType.OUT_OF_BOUNDS)drawHard(g,x*size,y*size,size,theme);else if(tile==TileType.BREAKABLE_BLOCK)drawBreakable(g,x*size,y*size,size,theme);}
            g.setColor(gridColor(theme));g.setStroke(new BasicStroke(1));for(int x=0;x<=width;x++)g.drawLine(Math.min(x*size,width*size-1),0,Math.min(x*size,width*size-1),height*size-1);for(int y=0;y<=height;y++)g.drawLine(0,Math.min(y*size,height*size-1),width*size-1,Math.min(y*size,height*size-1));
        }finally{g.dispose();}
    }
    public void markPlayerCell(Graphics2D graphics,TilePosition cell,int size){Graphics2D g=(Graphics2D)graphics.create();try{int x=cell.x()*size,y=cell.y()*size;g.setColor(new Color(49,190,255,42));g.fillRoundRect(x+2,y+2,size-4,size-4,8,8);g.setColor(new Color(75,205,255));g.setStroke(new BasicStroke(2.5f));g.drawRoundRect(x+2,y+2,size-5,size-5,8,8);}finally{g.dispose();}}

    private void drawFloor(Graphics2D g,int x,int y,int s,MapTheme theme){Color a,b,dot;switch(theme){case CLASSIC->{a=new Color(45,91,76);b=new Color(39,82,70);dot=new Color(255,255,255,8);}case FOREST->{a=new Color(50,112,60);b=new Color(43,99,53);dot=new Color(166,226,108,35);}case OCEAN->{a=new Color(52,151,184);b=new Color(45,139,173);dot=new Color(214,250,255,42);}case ICE->{a=new Color(174,222,238);b=new Color(157,209,230);dot=new Color(255,255,255,80);}default->{a=new Color(67,55,55);b=new Color(57,46,48);dot=new Color(255,92,40,35);}}
        g.setColor((x+y)%2==0?a:b);g.fillRect(x*s,y*s,s,s);g.setColor(dot);if(theme==MapTheme.OCEAN)g.drawArc(x*s+4,y*s+s/3,s-8,s/3,0,180);else if(theme==MapTheme.FIRE){g.drawLine(x*s+5,y*s+s-7,x*s+s/2,y*s+s/2);g.drawLine(x*s+s/2,y*s+s/2,x*s+s-5,y*s+s/2-4);}else g.fillOval(x*s+s/3,y*s+s/3,s/3,s/3);
    }
    private void drawHard(Graphics2D g,int x,int y,int s,MapTheme theme){switch(theme){case CLASSIC->drawStone(g,x,y,s);case FOREST->drawTree(g,x,y,s);case OCEAN->drawWhale(g,x,y,s);case ICE->drawPenguin(g,x,y,s);case FIRE->drawVolcano(g,x,y,s);}}
    private void drawBreakable(Graphics2D g,int x,int y,int s,MapTheme theme){switch(theme){case CLASSIC->drawCrate(g,x,y,s,new Color(194,125,53),new Color(83,45,24));case FOREST->drawTreeStump(g,x,y,s);case OCEAN->drawCoral(g,x,y,s);case ICE->drawSnowman(g,x,y,s);case FIRE->drawLavaRock(g,x,y,s);}}
    private Color gridColor(MapTheme theme){return switch(theme){case CLASSIC->new Color(11,35,31,90);case FOREST->new Color(20,62,31,80);case OCEAN->new Color(9,75,110,85);case ICE->new Color(78,145,177,70);case FIRE->new Color(30,20,25,110);};}

    private void drawStone(Graphics2D g,int x,int y,int s){g.setColor(new Color(65,76,98));g.fillRoundRect(x+2,y+2,s-4,s-4,5,5);g.setColor(new Color(112,128,155));g.drawLine(x+4,y+4,x+s-5,y+4);g.drawLine(x+4,y+4,x+4,y+s-5);g.setColor(new Color(27,34,49));g.drawLine(x+s-3,y+4,x+s-3,y+s-3);g.drawLine(x+4,y+s-3,x+s-3,y+s-3);g.setColor(new Color(46,57,77));g.drawLine(x+4,y+s/2,x+s-5,y+s/2);g.drawLine(x+s/2,y+4,x+s/2,y+s/2);}
    private void drawCrate(Graphics2D g,int x,int y,int s,Color wood,Color dark){g.setColor(dark);g.fillRoundRect(x+2,y+2,s-4,s-4,4,4);g.setColor(wood);g.fillRect(x+4,y+4,s-8,s-8);g.setStroke(new BasicStroke(Math.max(2,s*.09f)));g.setColor(wood.brighter());g.drawLine(x+6,y+6,x+s-7,y+s-7);g.drawLine(x+s-7,y+6,x+6,y+s-7);g.setStroke(new BasicStroke(1));}
    private void drawTree(Graphics2D g,int x,int y,int s){g.setColor(new Color(82,51,28));g.fillRoundRect(x+s/2-5,y+s/2,10,s/2-3,4,4);g.setColor(new Color(19,83,48));g.fillOval(x+3,y+3,s-6,s-8);g.setColor(new Color(38,139,66));g.fillOval(x+7,y+2,s-14,s/2+5);g.setColor(new Color(91,180,78));g.fillOval(x+10,y+6,7,6);}
    private void drawTreeStump(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(30,73,38,90));g.fillOval(x+3,y+s-7,s-6,5);
        g.setColor(new Color(91,52,28));g.fillRoundRect(x+7,y+10,s-14,s-14,5,5);
        g.setColor(new Color(159,99,48));g.fillOval(x+5,y+5,s-10,13);
        g.setColor(new Color(226,164,83));g.fillOval(x+8,y+7,s-16,9);
        g.setColor(new Color(119,70,34));g.drawOval(x+11,y+9,s-22,5);
        g.drawArc(x+13,y+10,s-26,3,20,280);
        g.setStroke(new BasicStroke(2));g.drawLine(x+8,y+17,x+3,y+23);g.drawLine(x+s-8,y+17,x+s-3,y+23);g.setStroke(new BasicStroke(1));
        g.setColor(new Color(70,132,53));g.fillOval(x+2,y+21,8,5);g.fillOval(x+s-10,y+22,8,5);
    }
    private void drawWhale(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(8,92,137,90));g.fillOval(x+4,y+s-7,s-8,5);
        g.setColor(new Color(32,104,153));g.fillOval(x+3,y+9,s-8,s-15);
        Path2D tail=new Path2D.Double();tail.moveTo(x+s-8,y+s/2);tail.lineTo(x+s-1,y+8);tail.lineTo(x+s-3,y+s/2);tail.lineTo(x+s-1,y+s-7);tail.closePath();g.fill(tail);
        g.setColor(new Color(184,226,239));g.fillOval(x+7,y+17,s-16,7);
        g.setColor(new Color(12,42,66));g.fillOval(x+9,y+13,3,3);g.setColor(Color.WHITE);g.fillOval(x+9,y+13,1,1);
        g.setColor(new Color(132,218,241));g.setStroke(new BasicStroke(2));g.drawArc(x+8,y+2,8,12,35,105);g.drawArc(x+12,y+1,8,13,35,105);g.setStroke(new BasicStroke(1));
    }
    private void drawCoral(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(207,181,121));g.fillOval(x+4,y+s-8,s-8,6);
        g.setColor(new Color(242,105,116));g.setStroke(new BasicStroke(5,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g.drawLine(x+s/2,y+s-7,x+s/2,y+8);g.drawLine(x+s/2,y+16,x+8,y+10);g.drawLine(x+s/2,y+19,x+s-8,y+11);
        g.setColor(new Color(255,151,126));g.setStroke(new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawLine(x+8,y+10,x+8,y+5);g.drawLine(x+s-8,y+11,x+s-6,y+6);g.drawLine(x+s/2,y+11,x+s/2+5,y+6);g.setStroke(new BasicStroke(1));
    }
    private void drawPenguin(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(55,120,150,90));g.fillOval(x+5,y+s-7,s-10,5);
        g.setColor(new Color(22,35,51));g.fillOval(x+6,y+3,s-12,s-6);
        g.setColor(new Color(240,250,252));g.fillOval(x+10,y+11,s-20,s-15);
        g.setColor(new Color(22,35,51));g.fillOval(x+9,y+8,5,6);g.fillOval(x+s-14,y+8,5,6);
        g.setColor(Color.WHITE);g.fillOval(x+10,y+9,2,2);g.fillOval(x+s-13,y+9,2,2);
        g.setColor(new Color(249,151,40));Path2D beak=new Path2D.Double();beak.moveTo(x+s/2-3,y+14);beak.lineTo(x+s/2+4,y+16);beak.lineTo(x+s/2-3,y+18);beak.closePath();g.fill(beak);
        g.fillOval(x+7,y+s-7,9,4);g.fillOval(x+s-16,y+s-7,9,4);
        g.setColor(new Color(45,160,210));g.fillRoundRect(x+7,y+18,s-14,4,3,3);g.fillRoundRect(x+s-11,y+20,4,7,3,3);
    }
    private void drawSnowman(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(72,137,164,80));g.fillOval(x+4,y+s-6,s-8,5);
        g.setColor(new Color(225,247,255));g.fillOval(x+6,y+14,s-12,s-17);g.setColor(new Color(246,253,255));g.fillOval(x+9,y+4,s-18,s-17);
        g.setColor(new Color(38,50,63));g.fillOval(x+12,y+10,3,3);g.fillOval(x+s-15,y+10,3,3);g.fillOval(x+s/2-1,y+22,3,3);g.fillOval(x+s/2-1,y+27,3,3);
        g.setColor(new Color(244,130,32));Path2D nose=new Path2D.Double();nose.moveTo(x+s/2,y+14);nose.lineTo(x+s/2+8,y+16);nose.lineTo(x+s/2,y+17);nose.closePath();g.fill(nose);
        g.setColor(new Color(211,47,67));g.fillRoundRect(x+8,y+17,s-16,4,3,3);g.fillRoundRect(x+s-12,y+19,4,7,3,3);
        g.setColor(new Color(29,42,56));g.fillRect(x+9,y+5,s-18,3);g.fillRoundRect(x+12,y,s-24,7,2,2);
        g.setColor(new Color(102,72,48));g.setStroke(new BasicStroke(2));g.drawLine(x+7,y+21,x+2,y+17);g.drawLine(x+s-7,y+21,x+s-2,y+17);g.setStroke(new BasicStroke(1));
    }
    private void drawVolcano(Graphics2D g,int x,int y,int s){
        g.setColor(new Color(40,25,29,100));g.fillOval(x+3,y+s-7,s-6,5);
        Path2D mountain=new Path2D.Double();mountain.moveTo(x+2,y+s-4);mountain.lineTo(x+s/2-5,y+8);mountain.lineTo(x+s/2+5,y+8);mountain.lineTo(x+s-2,y+s-4);mountain.closePath();g.setColor(new Color(71,52,52));g.fill(mountain);
        g.setColor(new Color(37,29,35));g.setStroke(new BasicStroke(2));g.draw(mountain);g.setColor(new Color(255,91,31));g.fillOval(x+s/2-6,y+6,12,5);
        Path2D lava=new Path2D.Double();lava.moveTo(x+s/2-3,y+9);lava.lineTo(x+s/2+2,y+9);lava.lineTo(x+s/2+6,y+s-5);lava.lineTo(x+s/2,y+s-9);lava.lineTo(x+s/2-4,y+s-5);lava.closePath();g.setColor(new Color(255,113,31));g.fill(lava);
        g.setColor(new Color(255,190,55));g.fillOval(x+s/2-2,y+1,4,5);
    }
    private void drawLavaRock(Graphics2D g,int x,int y,int s){
        Path2D rock=new Path2D.Double();rock.moveTo(x+4,y+s-5);rock.lineTo(x+6,y+10);rock.lineTo(x+13,y+4);rock.lineTo(x+s-7,y+8);rock.lineTo(x+s-3,y+s-7);rock.closePath();g.setColor(new Color(58,43,46));g.fill(rock);g.setColor(new Color(111,66,53));g.draw(rock);
        g.setColor(new Color(255,83,31));g.setStroke(new BasicStroke(2));g.drawLine(x+9,y+10,x+s/2,y+s/2);g.drawLine(x+s/2,y+s/2,x+s-8,y+s-8);g.drawLine(x+s/2,y+s/2,x+10,y+s-6);g.setColor(new Color(255,183,47));g.drawLine(x+10,y+10,x+s/2,y+s/2);g.setStroke(new BasicStroke(1));
    }
}

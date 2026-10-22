package com.bombit.client.game.movement;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.awt.*;
import java.util.List;

/** Visible board cells and the local player's server-confirmed cell; never predicts a bomb. */
public final class TileGridRenderer {
    public void render(Graphics2D graphics,int width,int height,int size,List<TileType> tiles){
        var g=(Graphics2D)graphics.create();
        try{
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                g.setColor(switch(tiles.get(y*width+x)){
                    case EMPTY -> (x+y)%2==0?new Color(235,242,235):new Color(221,232,221);
                    case HARD_WALL,OUT_OF_BOUNDS -> Color.DARK_GRAY;
                    case BREAKABLE_BLOCK -> new Color(180,120,60);
                });
                g.fillRect(x*size,y*size,size,size);
                switch(tiles.get(y*width+x)){
                    case HARD_WALL,OUT_OF_BOUNDS -> drawStone(g,x*size,y*size,size);
                    case BREAKABLE_BLOCK -> drawCrate(g,x*size,y*size,size);
                    default -> {}
                }
            }
            g.setColor(new Color(90,111,95));g.setStroke(new BasicStroke(1));
            for(int x=0;x<=width;x++)g.drawLine(Math.min(x*size,width*size-1),0,Math.min(x*size,width*size-1),height*size-1);
            for(int y=0;y<=height;y++)g.drawLine(0,Math.min(y*size,height*size-1),width*size-1,Math.min(y*size,height*size-1));
        }finally{g.dispose();}
    }
    public void markPlayerCell(Graphics2D graphics,TilePosition cell,int size){
        var g=(Graphics2D)graphics.create();
        try{
            int x=cell.x()*size,y=cell.y()*size;
            g.setColor(new Color(30,110,245,45));g.fillRect(x+2,y+2,size-4,size-4);
            g.setColor(new Color(30,110,245));g.setStroke(new BasicStroke(2));g.drawRect(x+2,y+2,size-5,size-5);
        }finally{g.dispose();}
    }
    private void drawStone(Graphics2D g,int x,int y,int size){
        g.setColor(new Color(94,105,119));g.fillRect(x+2,y+2,size-4,size-4);
        g.setColor(new Color(139,150,165));g.drawLine(x+3,y+3,x+size-4,y+3);g.drawLine(x+3,y+3,x+3,y+size-4);
        g.setColor(new Color(43,52,64));g.drawLine(x+size-3,y+3,x+size-3,y+size-3);g.drawLine(x+3,y+size-3,x+size-3,y+size-3);
        g.setColor(new Color(72,83,98));g.drawLine(x+4,y+size/2,x+size-5,y+size/2);
        g.drawLine(x+size/2,y+4,x+size/2,y+size/2);
    }
    private void drawCrate(Graphics2D g,int x,int y,int size){
        g.setColor(new Color(103,57,28));g.fillRect(x+2,y+2,size-4,size-4);
        g.setColor(new Color(194,137,67));g.fillRect(x+4,y+4,size-8,size-8);
        g.setColor(new Color(132,79,35));
        for(int i=1;i<3;i++)g.drawLine(x+4,y+size*i/3,x+size-5,y+size*i/3);
        g.setStroke(new BasicStroke(Math.max(2,size*.09f)));
        g.setColor(new Color(232,178,103));g.drawLine(x+6,y+6,x+size-7,y+size-7);g.drawLine(x+size-7,y+6,x+6,y+size-7);
        g.setStroke(new BasicStroke(1));g.setColor(new Color(75,45,25));
        for(int dx:new int[]{5,size-7})for(int dy:new int[]{5,size-7})g.fillRect(x+dx,y+dy,2,2);
    }
}

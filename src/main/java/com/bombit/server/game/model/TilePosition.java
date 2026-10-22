package com.bombit.server.game.model;

import java.util.Objects;

/** Tọa độ ô bất biến, không phải pixel. Cho phép tọa độ âm để duyệt tia nổ tới biên. */
public record TilePosition(int x,int y) {
    /** Half-open grid cells: a point exactly on an edge belongs to the cell to its right/bottom. */
    public static TilePosition containing(double pointX,double pointY,int tileSize){
        if(tileSize<=0||!Double.isFinite(pointX)||!Double.isFinite(pointY))throw new IllegalArgumentException("Invalid grid point");
        return new TilePosition((int)Math.floor(pointX/tileSize),(int)Math.floor(pointY/tileSize));
    }
    public TilePosition move(Direction direction,int distance) {
        Objects.requireNonNull(direction);
        return new TilePosition(Math.addExact(x,Math.multiplyExact(direction.deltaX(),distance)),
                Math.addExact(y,Math.multiplyExact(direction.deltaY(),distance)));
    }
}

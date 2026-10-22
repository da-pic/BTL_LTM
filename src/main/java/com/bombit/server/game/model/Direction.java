package com.bombit.server.game.model;

/** Hướng gameplay chung. Trục X tăng sang phải, Y tăng xuống dưới. */
public enum Direction {
    UP(0,-1), DOWN(0,1), LEFT(-1,0), RIGHT(1,0);
    private final int dx,dy;
    Direction(int dx,int dy) { this.dx=dx; this.dy=dy; }
    public int deltaX() { return dx; }
    public int deltaY() { return dy; }
}

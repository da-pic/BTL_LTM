package com.bombit.server.game.model;

/** Loại ô nền; bomb/item là thực thể riêng. OUT_OF_BOUNDS chỉ dùng khi truy vấn ngoài map. */
public enum TileType {
    EMPTY, HARD_WALL, BREAKABLE_BLOCK, OUT_OF_BOUNDS;
    public boolean isWalkable() { return this == EMPTY; }
}

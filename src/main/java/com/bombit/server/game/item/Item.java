package com.bombit.server.game.item;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Objects;

/** Thực thể vật phẩm do server nắm quyền quyết định. */
public final class Item {
    private final int id;
    private final ItemType type;
    private final TilePosition position;
    private ItemState state;

    public Item(int id, ItemType type, TilePosition position) {
        if (id <= 0) {
            throw new IllegalArgumentException("Item id must be positive");
        }
        this.id = id;
        this.type = Objects.requireNonNull(type);
        this.position = Objects.requireNonNull(position);
        this.state = ItemState.AVAILABLE;
    }

    public int id() {
        return id;
    }

    public ItemType type() {
        return type;
    }

    public TilePosition position() {
        return position;
    }

    public ItemState state() {
        return state;
    }

    public boolean pickUp() {
        if (state != ItemState.AVAILABLE) {
            return false;
        }
        state = ItemState.PICKED_UP;
        return true;
    }

    public boolean remove() {
        if (state == ItemState.REMOVED) {
            return false;
        }
        state = ItemState.REMOVED;
        return true;
    }
}

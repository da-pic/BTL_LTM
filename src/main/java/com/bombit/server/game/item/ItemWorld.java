package com.bombit.server.game.item;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Optional;

/** Ranh giới tích hợp do bộ chuyển đổi GameState của TV2 triển khai. */
public interface ItemWorld {
    boolean isPlayerAlive(String playerId);

    TilePosition playerPosition(String playerId);

    Optional<Item> itemAt(TilePosition position);

    void addItem(Item item);

    void removeItem(int itemId);

    void applyItemEffect(String playerId, ItemType type);
}

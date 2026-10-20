package com.bombit.client.game.item;

import com.bombit.server.game.bomb.TilePosition;
import com.bombit.server.game.item.ItemType;

public record ItemView(int itemId, ItemType type, TilePosition position) {
}

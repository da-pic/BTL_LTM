package com.bombit.client.ui.game.item;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.item.ItemType;

public record ItemView(int itemId, ItemType type, TilePosition position) {
}

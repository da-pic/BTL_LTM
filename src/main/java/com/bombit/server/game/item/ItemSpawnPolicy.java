package com.bombit.server.game.item;

import com.bombit.server.game.model.TilePosition;

import java.util.Optional;

@FunctionalInterface
public interface ItemSpawnPolicy {
    Optional<ItemType> chooseItem(TilePosition destroyedBlock);
}

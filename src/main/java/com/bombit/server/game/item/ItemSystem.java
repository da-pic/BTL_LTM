package com.bombit.server.game.item;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.event.GameEventSink;
import com.bombit.server.game.event.ItemPickedUpEvent;
import com.bombit.server.game.event.ItemSpawnedEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Luật sinh và nhặt vật phẩm phía server. Được gọi từ Game Loop. */
public final class ItemSystem {
    private final AtomicInteger nextItemId;
    private final ItemSpawnPolicy spawnPolicy;
    private final GameEventSink eventSink;

    public ItemSystem(ItemSpawnPolicy spawnPolicy, GameEventSink eventSink) {
        this(spawnPolicy, eventSink, 1);
    }

    ItemSystem(ItemSpawnPolicy spawnPolicy, GameEventSink eventSink, int firstItemId) {
        this.spawnPolicy = Objects.requireNonNull(spawnPolicy);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.nextItemId = new AtomicInteger(firstItemId);
    }

    public Optional<Item> trySpawn(TilePosition destroyedBlock, long nowMillis, ItemWorld world) {
        Objects.requireNonNull(destroyedBlock);
        Objects.requireNonNull(world);
        if (world.itemAt(destroyedBlock).isPresent()) {
            return Optional.empty();
        }

        Optional<ItemType> selectedType = spawnPolicy.chooseItem(destroyedBlock);
        if (selectedType.isEmpty()) {
            return Optional.empty();
        }

        Item item = new Item(nextItemId.getAndIncrement(), selectedType.get(), destroyedBlock);
        world.addItem(item);
        eventSink.publish(new ItemSpawnedEvent(nowMillis, item.id(), item.type(), item.position()));
        return Optional.of(item);
    }

    public boolean tryPickUp(String playerId, long nowMillis, ItemWorld world) {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(world);
        if (!world.isPlayerAlive(playerId)) {
            return false;
        }

        Optional<Item> candidate = world.itemAt(world.playerPosition(playerId));
        if (candidate.isEmpty() || !candidate.get().pickUp()) {
            return false;
        }

        Item item = candidate.get();
        world.removeItem(item.id());
        world.applyItemEffect(playerId, item.type());
        eventSink.publish(new ItemPickedUpEvent(nowMillis, item.id(), playerId, item.type()));
        return true;
    }
}

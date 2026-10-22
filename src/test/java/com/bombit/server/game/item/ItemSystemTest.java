package com.bombit.server.game.item;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.event.GameEvent;
import com.bombit.server.game.event.ItemPickedUpEvent;
import com.bombit.server.game.event.ItemSpawnedEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSystemTest {
    @Test
    void spawnsAndPicksUpItemOnlyOnce() {
        List<GameEvent> events = new ArrayList<>();
        FakeItemWorld world = new FakeItemWorld();
        TilePosition position = new TilePosition(4, 3);
        world.playerPositions.put("p1", position);
        world.alivePlayers.add("p1");
        ItemSystem system = new ItemSystem(block -> Optional.of(ItemType.FIRE_UP), events::add);

        Optional<Item> spawned = system.trySpawn(position, 100, world);
        boolean firstPickup = system.tryPickUp("p1", 200, world);
        boolean secondPickup = system.tryPickUp("p1", 201, world);

        assertTrue(spawned.isPresent());
        assertTrue(firstPickup);
        assertFalse(secondPickup);
        assertEquals(List.of(ItemType.FIRE_UP), world.appliedEffects);
        assertEquals(1, events.stream().filter(ItemSpawnedEvent.class::isInstance).count());
        assertEquals(1, events.stream().filter(ItemPickedUpEvent.class::isInstance).count());
    }

    @Test
    void doesNotSpawnOverExistingItem() {
        FakeItemWorld world = new FakeItemWorld();
        TilePosition position = new TilePosition(1, 1);
        world.addItem(new Item(99, ItemType.BOMB_UP, position));
        ItemSystem system = new ItemSystem(block -> Optional.of(ItemType.SPEED_UP), event -> { });

        Optional<Item> result = system.trySpawn(position, 100, world);

        assertTrue(result.isEmpty());
        assertEquals(1, world.items.size());
    }

    private static final class FakeItemWorld implements ItemWorld {
        private final Map<String, TilePosition> playerPositions = new HashMap<>();
        private final Set<String> alivePlayers = new HashSet<>();
        private final Map<Integer, Item> items = new HashMap<>();
        private final List<ItemType> appliedEffects = new ArrayList<>();

        @Override
        public boolean isPlayerAlive(String playerId) {
            return alivePlayers.contains(playerId);
        }

        @Override
        public TilePosition playerPosition(String playerId) {
            return playerPositions.get(playerId);
        }

        @Override
        public Optional<Item> itemAt(TilePosition position) {
            return items.values().stream().filter(item -> item.position().equals(position)).findFirst();
        }

        @Override
        public void addItem(Item item) {
            items.put(item.id(), item);
        }

        @Override
        public void removeItem(int itemId) {
            items.remove(itemId);
        }

        @Override
        public void applyItemEffect(String playerId, ItemType type) {
            appliedEffects.add(type);
        }
    }
}

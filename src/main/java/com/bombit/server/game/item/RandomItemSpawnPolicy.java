package com.bombit.server.game.item;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Chính sách rơi vật phẩm ngẫu nhiên có thể cấu hình; dùng bộ sinh có seed khi kiểm thử. */
public final class RandomItemSpawnPolicy implements ItemSpawnPolicy {
    private final double dropChance;
    private final RandomGenerator random;

    public RandomItemSpawnPolicy(double dropChance, RandomGenerator random) {
        if (dropChance < 0 || dropChance > 1) {
            throw new IllegalArgumentException("Drop chance must be between 0 and 1");
        }
        this.dropChance = dropChance;
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public Optional<ItemType> chooseItem(TilePosition destroyedBlock) {
        if (random.nextDouble() >= dropChance) {
            return Optional.empty();
        }
        ItemType[] types = ItemType.values();
        return Optional.of(types[random.nextInt(types.length)]);
    }
}

package com.bombit.server.game.mode.paradox;

import com.bombit.server.game.item.*;
import com.bombit.server.game.model.TilePosition;
import java.util.Optional;

/** Chính sách drop riêng Paradox; Classic/Shadow vẫn giữ policy đã cấu hình. */
public final class ParadoxItemSpawnPolicy implements CheckpointItemSpawnPolicy {
    private final ParadoxRandom random;
    private final double chance;

    public ParadoxItemSpawnPolicy(double chance, long seed) {
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("Invalid drop chance");
        this.chance = chance; random = new ParadoxRandom(seed);
    }

    public Optional<ItemType> chooseItem(TilePosition cell) {
        double sample = (random.nextLong() >>> 11) * 0x1.0p-53;
        if (sample >= chance) return Optional.empty();
        var types = ItemType.values();
        return Optional.of(types[random.nextInt(types.length)]);
    }
    public Long captureCheckpoint() { return random.captureCheckpoint(); }
    public void validateRestore(Long checkpoint) { random.validateRestore(checkpoint); }
    public void restoreCheckpoint(Long checkpoint) { random.restoreCheckpoint(checkpoint); }
}

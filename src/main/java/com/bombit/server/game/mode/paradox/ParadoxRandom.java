package com.bombit.server.game.mode.paradox;

import com.bombit.server.game.checkpoint.CheckpointParticipant;
import java.util.Objects;

/** RNG SplitMix64 riêng cho mô phỏng: restore cả trạng thái để không đổi drop sau rewind. */
public final class ParadoxRandom implements CheckpointParticipant<Long> {
    private long state;

    public ParadoxRandom(long seed) { state = seed; }

    public long nextLong() {
        long value = (state += 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** Rejection sampling tránh thiên lệch modulo, kể cả bound không phải lũy thừa hai. */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Positive bound required");
        long bits, result;
        do {
            bits = nextLong() >>> 1;
            result = bits % bound;
        } while (bits - result + bound - 1 < 0);
        return (int) result;
    }

    public Long captureCheckpoint() { return state; }
    public void validateRestore(Long checkpoint) { Objects.requireNonNull(checkpoint); }
    public void restoreCheckpoint(Long checkpoint) { validateRestore(checkpoint); state = checkpoint; }
}

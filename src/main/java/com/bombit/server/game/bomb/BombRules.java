package com.bombit.server.game.bomb;

/** Explicit per-match rules; no implicit decision about game balance. */
public record BombRules(long fuseMillis, int range, int quota) {
    public BombRules {
        if(fuseMillis<=0 || fuseMillis>Long.MAX_VALUE/1_000_000 || range<=0 || quota<=0)
            throw new IllegalArgumentException("Invalid bomb rules");
    }
}

package com.bombit.common.dto;

import com.bombit.server.game.model.TilePosition;
import java.util.*;

/** Authoritative visual effects, not persistent damage. One immutable frame per published tick. */
public record BombFrame(long serverTimeMillis, List<Explosion> explosions) {
    public static final long ANIMATION_MILLIS = 600;
    public BombFrame {
        explosions = List.copyOf(explosions);
        if (explosions.size() > 256 || explosions.stream().map(Explosion::bombId).distinct().count() != explosions.size())
            throw new IllegalArgumentException("Invalid explosion list");
    }
    public record Explosion(int bombId, long startedAtMillis, long visibleUntilMillis, Set<TilePosition> cells) {
        public Explosion {
            cells = Set.copyOf(cells);
            if (bombId <= 0 || visibleUntilMillis <= startedAtMillis || cells.isEmpty() || cells.size() > 1024)
                throw new IllegalArgumentException("Invalid explosion");
        }
    }
}

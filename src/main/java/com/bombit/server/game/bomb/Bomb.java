package com.bombit.server.game.bomb;

import com.bombit.server.game.model.TilePosition;

import java.util.Objects;

/** Thực thể bom do server nắm quyền quyết định. */
public final class Bomb {
    private final int id;
    private final String ownerPlayerId;
    private final TilePosition position;
    private final long placedAtMillis;
    private final long fuseMillis;
    private final int range;
    private BombState state;

    public Bomb(int id, String ownerPlayerId, TilePosition position, long placedAtMillis, long fuseMillis, int range) {
        if (id <= 0) {
            throw new IllegalArgumentException("Bomb id must be positive");
        }
        if (fuseMillis <= 0) {
            throw new IllegalArgumentException("Bomb fuse must be positive");
        }
        if (range <= 0) {
            throw new IllegalArgumentException("Bomb range must be positive");
        }
        this.id = id;
        this.ownerPlayerId = Objects.requireNonNull(ownerPlayerId);
        this.position = Objects.requireNonNull(position);
        this.placedAtMillis = placedAtMillis;
        this.fuseMillis = fuseMillis;
        this.range = range;
        this.state = BombState.ARMED;
    }

    public int id() {
        return id;
    }

    public String ownerPlayerId() {
        return ownerPlayerId;
    }

    public TilePosition position() {
        return position;
    }

    public long placedAtMillis() {
        return placedAtMillis;
    }

    public long fuseMillis() {
        return fuseMillis;
    }

    public long explodeAtMillis() {
        return placedAtMillis + fuseMillis;
    }

    public int range() {
        return range;
    }

    public BombState state() {
        return state;
    }

    public boolean queueExplosion() {
        if (state != BombState.ARMED) {
            return false;
        }
        state = BombState.QUEUED_TO_EXPLODE;
        return true;
    }

    public boolean markExploded() {
        if (state == BombState.EXPLODED || state == BombState.CANCELLED) {
            return false;
        }
        state = BombState.EXPLODED;
        return true;
    }

    public boolean cancel() {
        if (state == BombState.EXPLODED || state == BombState.CANCELLED) {
            return false;
        }
        state = BombState.CANCELLED;
        return true;
    }
}

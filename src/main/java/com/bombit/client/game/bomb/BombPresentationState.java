package com.bombit.client.game.bomb;

import com.bombit.server.game.bomb.TilePosition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Trạng thái client không phụ thuộc khung giao diện, dành cho bộ kết xuất sau này sử dụng. */
public final class BombPresentationState {
    private final Map<Integer, BombView> bombs = new ConcurrentHashMap<>();
    private final Map<Integer, ExplosionView> explosions = new ConcurrentHashMap<>();

    public void bombPlaced(int bombId, TilePosition position, long explodeAtMillis, int range) {
        bombs.put(bombId, new BombView(bombId, position, explodeAtMillis, range));
    }

    public void bombExploded(int bombId, Collection<TilePosition> cells, long nowMillis, long animationMillis) {
        bombs.remove(bombId);
        explosions.put(bombId, new ExplosionView(
                bombId,
                Set.copyOf(cells),
                nowMillis,
                nowMillis + animationMillis));
    }

    public void removeExpiredExplosions(long nowMillis) {
        explosions.values().removeIf(explosion -> explosion.visibleUntilMillis() <= nowMillis);
    }

    public Collection<BombView> bombs() {
        return List.copyOf(bombs.values());
    }

    public Collection<ExplosionView> explosions() {
        return List.copyOf(explosions.values());
    }

    public void replaceBombs(Collection<BombView> snapshotBombs) {
        bombs.clear();
        snapshotBombs.forEach(bomb -> bombs.put(bomb.bombId(), bomb));
    }
}

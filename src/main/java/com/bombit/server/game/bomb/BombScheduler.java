package com.bombit.server.game.bomb;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Lập lịch thời điểm hết hạn ngòi nổ và chỉ đưa mã bom vào hàng đợi cho Game Loop. */
public final class BombScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final IntConsumer explosionCommandSink;
    private final Map<Integer, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public BombScheduler(ScheduledExecutorService executor, IntConsumer explosionCommandSink) {
        this.executor = Objects.requireNonNull(executor);
        this.explosionCommandSink = Objects.requireNonNull(explosionCommandSink);
    }

    public void schedule(Bomb bomb) {
        Objects.requireNonNull(bomb);
        ScheduledFuture<?> task = executor.schedule(
                () -> {
                    if (bomb.queueExplosion()) {
                        explosionCommandSink.accept(bomb.id());
                    }
                    tasks.remove(bomb.id());
                },
                bomb.fuseMillis(),
                TimeUnit.MILLISECONDS);
        ScheduledFuture<?> oldTask = tasks.putIfAbsent(bomb.id(), task);
        if (oldTask != null) {
            task.cancel(false);
            throw new IllegalStateException("Bomb is already scheduled: " + bomb.id());
        }
    }

    public void cancel(int bombId) {
        ScheduledFuture<?> task = tasks.remove(bombId);
        if (task != null) {
            task.cancel(false);
        }
    }

    @Override
    public void close() {
        tasks.values().forEach(task -> task.cancel(false));
        tasks.clear();
    }
}

package com.bombit.server.game.bomb;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombSchedulerTest {
    @Test
    void schedulerOnlyQueuesBombId() throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch queued = new CountDownLatch(1);
        AtomicInteger queuedBombId = new AtomicInteger();
        Bomb bomb = new Bomb(7, "p1", new TilePosition(1, 1), 0, 20, 1);

        try (BombScheduler scheduler = new BombScheduler(executor, bombId -> {
            queuedBombId.set(bombId);
            queued.countDown();
        })) {
            scheduler.schedule(bomb);
            assertTrue(queued.await(1, TimeUnit.SECONDS));
            assertEquals(7, queuedBombId.get());
            assertEquals(BombState.QUEUED_TO_EXPLODE, bomb.state());
        } finally {
            executor.shutdownNow();
        }
    }
}

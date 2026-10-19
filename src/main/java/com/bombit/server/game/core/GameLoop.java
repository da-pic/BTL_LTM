package com.bombit.server.game.core;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.bombit.server.game.movement.GameStateUpdate;
import com.bombit.server.game.movement.MovementSystem;
import com.bombit.server.game.movement.StateDeltaBuilder;

public class GameLoop {

    private static final int TICKS_PER_SECOND = 20;

    private static final long TICK_NANOS =
            1_000_000_000L / TICKS_PER_SECOND;

    private static final double DELTA_TIME_SECONDS =
            1.0 / TICKS_PER_SECOND;

    private static final int MAX_COMMANDS_PER_TICK = 256;
    private static final int MAX_COMMAND_QUEUE_SIZE = 4096;
    private static final int MAX_OUTBOUND_QUEUE_SIZE = 1024;

    // Gui lai full state moi 100 tick, khoang 5 giay.
    private static final long FULL_STATE_INTERVAL_TICKS = 100;

    private final GameState gameState;
    private final MovementSystem movementSystem;
    private final StateDeltaBuilder deltaBuilder =
            new StateDeltaBuilder();

    private final BlockingQueue<GameCommand> commandQueue =
            new LinkedBlockingQueue<>(MAX_COMMAND_QUEUE_SIZE);

    // TV4 se doc update tu queue nay de ma hoa va broadcast.
    private final BlockingQueue<GameStateUpdate> outboundQueue =
            new LinkedBlockingQueue<>(MAX_OUTBOUND_QUEUE_SIZE);

    private volatile boolean running = false;
    private volatile long tick = 0;
    private volatile long processedCommandCount = 0;
    private volatile long outboundQueueFullCount = 0;

    private Thread loopThread;

    public GameLoop(GameState gameState) {
        this(gameState, new MovementSystem());
    }

    public GameLoop(
            GameState gameState,
            MovementSystem movementSystem) {

        this.gameState = Objects.requireNonNull(
                gameState, "gameState khong duoc null");

        this.movementSystem = Objects.requireNonNull(
                movementSystem, "movementSystem khong duoc null");
    }

    public synchronized void start() {

        if (loopThread != null) {
            throw new IllegalStateException(
                    "GameLoop nay chi duoc khoi dong mot lan");
        }

        if (gameState.getMatchState()
                != GameState.MatchState.RUNNING) {

            throw new IllegalStateException(
                    "Phai startMatch() truoc khi khoi dong GameLoop");
        }

        running = true;

        loopThread = new Thread(
                this::runLoop, "GameLoop");

        loopThread.start();
    }

    // Module khac gui command vao GameLoop.
    public boolean submitCommand(GameCommand command) {

        Objects.requireNonNull(
                command, "command khong duoc null");

        if (!running) {
            return false;
        }

        // Tra ve false neu queue da day.
        return commandQueue.offer(command);
    }

    private void runLoop() {

        long nextTick = System.nanoTime();

        try {
            while (running) {

                // 1. Xu ly cac command dang cho.
                processCommands();

                // 2. Cap nhat movement va collision.
                movementSystem.update(
                        gameState,
                        DELTA_TIME_SECONDS
                );

                // 3. Tang tick sau khi da cap nhat trang thai.
                tick++;

                // 4. Tao FULL_STATE hoac DELTA.
                publishStateUpdate();

                // 5. Canh thoi gian tick tiep theo.
                nextTick += TICK_NANOS;

                long remaining =
                        nextTick - System.nanoTime();

                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.sleep(remaining);
                } else {
                    // Khong chay bu hang loat tick bi tre.
                    nextTick = System.nanoTime();
                }
            }

        } catch (InterruptedException e) {

            if (running) {
                Thread.currentThread().interrupt();
            }

        } finally {
            running = false;
        }
    }

    private void processCommands() {

        for (int i = 0; i < MAX_COMMANDS_PER_TICK; i++) {

            GameCommand command = commandQueue.poll();

            if (command == null) {
                break;
            }

            movementSystem.applyCommand(
                    gameState, command);

            processedCommandCount++;
        }
    }

    private void publishStateUpdate() {

        boolean forceFullState =
                tick == 1
                || tick % FULL_STATE_INTERVAL_TICKS == 0;

        GameStateUpdate update =
                deltaBuilder.buildUpdate(
                        gameState,
                        tick,
                        forceFullState
                );

        // Khong co thay doi thi khong gui delta rong.
        if (update == null) {
            return;
        }

        // Chi cap nhat baseline neu update da vao queue.
        if (outboundQueue.offer(update)) {

            deltaBuilder.commit(update);

        } else {

            // Queue day: khong commit baseline.
            // Lan tiep theo se so sanh voi baseline cu.
            outboundQueueFullCount++;
        }
    }

    // TV4 co the goi tu network worker rieng.
    public GameStateUpdate takeOutboundUpdate()
            throws InterruptedException {

        return outboundQueue.take();
    }

    // Dung cho kiem thu hoac doc queue khong block.
    public GameStateUpdate pollOutboundUpdate() {
        return outboundQueue.poll();
    }

    public int getPendingOutboundUpdateCount() {
        return outboundQueue.size();
    }

    public void stop() {

        Thread thread;

        synchronized (this) {
            running = false;
            thread = loopThread;
        }

        if (thread != null) {

            thread.interrupt();

            if (thread != Thread.currentThread()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getTick() {
        return tick;
    }

    public long getProcessedCommandCount() {
        return processedCommandCount;
    }

    public long getOutboundQueueFullCount() {
        return outboundQueueFullCount;
    }
}
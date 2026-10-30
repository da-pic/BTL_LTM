package com.bombit.server.game.mode.paradox;

import java.util.*;
import java.util.function.Consumer;

/**
 * Lịch sử hữu hạn dành riêng Paradox, sở hữu bởi game thread tạo đối tượng.
 * C là checkpoint đầy đủ, bất biến do coordinator gameplay cung cấp, không phải DTO mạng.
 * Tick truyền tải/epoch/lượt dùng không nằm trong C nên không bị hoàn lại.
 */
public final class ParadoxTimeline<C> {
    public static final int REWIND_TICKS = 40;
    public static final int HISTORY_CAPACITY = 61; // Bao gồm tick hiện tại và 60 tick trước.
    public static final long COOLDOWN_NANOS = 3_000_000_000L;
    public enum Outcome { ACCEPTED, NOT_RUNNING, UNKNOWN_PLAYER, NOT_ELIGIBLE,
        ALREADY_USED, REWIND_BUSY, COOLDOWN, NOT_ENOUGH_HISTORY }

    private final Thread owner = Thread.currentThread();
    private final Set<Integer> roster;
    private final Set<Integer> used = new HashSet<>();
    private final NavigableMap<Long, C> history = new TreeMap<>();
    private long serverTick, simulationTick, timelineEpoch;
    private long lastRewindServerTick = -1, lastRewindNanos;
    private boolean hasRewound, finished;

    public ParadoxTimeline(Collection<Integer> players) {
        roster = Set.copyOf(players);
        if (roster.size() < 2 || roster.size() > 4 || roster.size() != players.size()
                || roster.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException("Paradox requires 2-4 unique players");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Timeline belongs to game thread");
    }

    /** Gọi đúng một lần đầu mỗi tick; không dùng simulationTick làm version mạng. */
    public void advanceTick() {
        beginStep(); completeSimulationTick();
    }
    public void beginStep() {
        checkThread();
        if (finished) throw new IllegalStateException("Finished timeline");
        serverTick = Math.incrementExact(serverTick);
    }
    public void completeSimulationTick() {
        checkThread();
        if (finished) throw new IllegalStateException("Finished timeline");
        simulationTick = Math.incrementExact(simulationTick);
    }

    /** Chụp cuối tick; có thể chụp tick 0 ngay sau khởi tạo gameplay. */
    public void capture(C checkpoint) {
        checkThread();
        if (finished) throw new IllegalStateException("Finished timeline");
        history.put(simulationTick, Objects.requireNonNull(checkpoint));
        while (history.size() > HISTORY_CAPACITY) history.pollFirstEntry();
    }

    /**
     * Caller xác thực session/epoch rồi truyền điều kiện sống, frozen và RUNNING hiện tại.
     * Restore phải hoàn tất trước khi tiêu lượt. Lỗi restore phải dừng runtime, không tiếp tục
     * mô phỏng world có thể đã sửa một phần. Không bắt lỗi rồi coi là rewind thành công.
     */
    public Outcome rewind(int playerId, boolean running, boolean eligible, long nowNanos, Consumer<C> restore) {
        checkThread(); Objects.requireNonNull(restore);
        if (finished || !running) return Outcome.NOT_RUNNING;
        if (!roster.contains(playerId)) return Outcome.UNKNOWN_PLAYER;
        if (!eligible) return Outcome.NOT_ELIGIBLE;
        if (lastRewindServerTick == serverTick) return Outcome.REWIND_BUSY;
        if (used.contains(playerId)) return Outcome.ALREADY_USED;
        if (hasRewound && nowNanos - lastRewindNanos < COOLDOWN_NANOS) return Outcome.COOLDOWN;
        long target = simulationTick - REWIND_TICKS;
        C checkpoint = history.get(target);
        if (checkpoint == null) return Outcome.NOT_ENOUGH_HISTORY;
        long nextEpoch = Math.incrementExact(timelineEpoch);
        restore.accept(checkpoint);
        // Xóa tương lai cũ; các lần capture sau thuộc nhánh mới, không đọc lại nhánh đã bỏ.
        history.tailMap(target, false).clear();
        simulationTick = target;
        timelineEpoch = nextEpoch;
        used.add(playerId);
        lastRewindServerTick = serverTick;
        lastRewindNanos = nowNanos;
        hasRewound = true;
        return Outcome.ACCEPTED;
    }

    /** Kết quả đã chốt là bất khả đảo; giải phóng lịch sử ngay khi kết thúc. */
    public void finish() { checkThread(); finished = true; history.clear(); }
    public long serverTick() { checkThread(); return serverTick; }
    public long simulationTick() { checkThread(); return simulationTick; }
    public long timelineEpoch() { checkThread(); return timelineEpoch; }
    public boolean hasCharge(int playerId) { checkThread(); return roster.contains(playerId) && !used.contains(playerId); }
    public int historySize() { checkThread(); return history.size(); }
}

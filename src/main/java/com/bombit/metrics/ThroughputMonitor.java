package com.bombit.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe bộ đếm traffic và tốc độ packets/s, bytes/s trên cửa sổ quan sát. */
public final class ThroughputMonitor {
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong totalPackets = new AtomicLong();
    private final AtomicLong windowBytes = new AtomicLong();
    private final AtomicLong windowPackets = new AtomicLong();
    private final AtomicLong windowStartedNanos = new AtomicLong(System.nanoTime());

    /** Ghi nhận kích thước frame thực tế (4 byte length + body). */
    public void addPacket(int packetSizeInBytes) {
        if (packetSizeInBytes < 0) throw new IllegalArgumentException("Kích thước packet không thể âm");
        totalPackets.incrementAndGet(); totalBytes.addAndGet(packetSizeInBytes);
        windowPackets.incrementAndGet(); windowBytes.addAndGet(packetSizeInBytes);
    }

    public long getTotalBytes() { return totalBytes.get(); }
    public long getTotalPackets() { return totalPackets.get(); }

    /** Tốc độ tính từ khi bắt đầu cửa sổ hoặc lần gọi resetRates gần nhất. */
    public double getPacketsPerSecond() { return rate(windowPackets.get()); }
    public double getBytesPerSecond() { return rate(windowBytes.get()); }

    private double rate(long count) {
        long elapsed = Math.max(1, System.nanoTime() - windowStartedNanos.get());
        return count * 1_000_000_000.0 / elapsed;
    }

    public void resetRates() {
        windowPackets.set(0); windowBytes.set(0); windowStartedNanos.set(System.nanoTime());
    }

    /** Reset cả tổng traffic lẫn tốc độ để bắt đầu một phép đo mới. */
    public void reset() {
        totalBytes.set(0); totalPackets.set(0); resetRates();
    }
}

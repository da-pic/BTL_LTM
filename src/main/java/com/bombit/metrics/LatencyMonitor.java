package com.bombit.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Theo dõi RTT bằng nanoTime, lưu mẫu ms và tính avg/min/max/p95. */
public final class LatencyMonitor {
    private final int maxSamples;
    private final List<Double> samplesMs = new ArrayList<>();
    // pingId là int theo protocol, dùng để ghép PONG với PING tương ứng.
    private final ConcurrentMap<Integer, Long> pendingPings = new ConcurrentHashMap<>();

    public LatencyMonitor() { this(10_000); }
    public LatencyMonitor(int maxSamples) {
        if (maxSamples < 1) throw new IllegalArgumentException("maxSamples phải lớn hơn 0");
        this.maxSamples = maxSamples;
    }

    /** Ghi lại thời điểm bắt đầu để ghép với PONG trả về sau đó. */
    public long startPing(int pingId) {
        long sentAt = System.nanoTime();
        pendingPings.put(pingId, sentAt);
        return sentAt;
    }

    /** Nhận PONG; trả false nếu pingId lạ hoặc đã được ghi nhận trước đó. */
    public boolean recordPong(int pingId) {
        Long sentAt = pendingPings.remove(pingId);
        if (sentAt == null) return false;
        recordElapsedNanos(System.nanoTime() - sentAt);
        return true;
    }

    /** Tương thích với API cũ: truyền timestamp nanoTime lúc gửi. */
    public void recordSample(long sendTimestampNano) {
        recordElapsedNanos(System.nanoTime() - sendTimestampNano);
    }

    /** Ghi duration trực tiếp để test và tích hợp monitor bên ngoài. */
    public synchronized void recordSampleNanos(long rttNanos) {
        if (rttNanos < 0) throw new IllegalArgumentException("RTT không thể âm");
        samplesMs.add(rttNanos / 1_000_000.0);
        if (samplesMs.size() > maxSamples) samplesMs.remove(0); // Giữ bộ nhớ có giới hạn.
    }

    private void recordElapsedNanos(long elapsedNanos) { recordSampleNanos(Math.max(0, elapsedNanos)); }
    public synchronized int getSampleCount() { return samplesMs.size(); }

    public synchronized double getAverageRTT() {
        if (samplesMs.isEmpty()) return 0.0;
        double sum = 0;
        for (double sample : samplesMs) sum += sample;
        return sum / samplesMs.size();
    }

    public synchronized double getMinRTT() { return samplesMs.stream().mapToDouble(Double::doubleValue).min().orElse(0); }
    public synchronized double getMaxRTT() { return samplesMs.stream().mapToDouble(Double::doubleValue).max().orElse(0); }

    /** P95 theo nearest-rank: phần tử ở vị trí ceil(0.95*n), đánh số từ 1. */
    public synchronized double getP95RTT() {
        if (samplesMs.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(samplesMs);
        Collections.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    public synchronized List<Double> getSamplesMs() { return List.copyOf(samplesMs); }
    public synchronized void reset() { samplesMs.clear(); pendingPings.clear(); }
}

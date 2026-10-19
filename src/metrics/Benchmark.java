package metrics;

import client.network.ClientNetwork;
import common.protocol.Packet;
import common.protocol.PacketType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Mở song song nhiều client TCP giả lập và báo kết quả kết nối / ping thử. */
public final class Benchmark {
    private Benchmark() { }

    public static final class Result {
        private final int requested, connected, failed;
        private final long elapsedMillis;
        private Result(int requested, int connected, int failed, long elapsedMillis) {
            this.requested = requested; this.connected = connected; this.failed = failed; this.elapsedMillis = elapsedMillis;
        }
        public int getRequested() { return requested; }
        public int getConnected() { return connected; }
        public int getFailed() { return failed; }
        public long getElapsedMillis() { return elapsedMillis; }
        @Override public String toString() {
            return "Benchmark{requested=" + requested + ", connected=" + connected + ", failed=" + failed
                    + ", elapsedMs=" + elapsedMillis + "}";
        }
    }

    /** Blocking overload: trả sau khi mọi client đã hoàn tất hoặc hết timeout. */
    public static Result runConcurrentClients(int clientCount, String host, int port) throws InterruptedException {
        return runConcurrentClients(clientCount, host, port, 1_000, 15_000);
    }

    public static Result runConcurrentClients(int clientCount, String host, int port,
                                               long holdMillis, long timeoutMillis) throws InterruptedException {
        if (clientCount < 1) throw new IllegalArgumentException("clientCount phải lớn hơn 0");
        if (holdMillis < 0 || timeoutMillis < 1) throw new IllegalArgumentException("Timeout/thời gian không hợp lệ");
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(clientCount);
        List<Thread> workers = new ArrayList<>(clientCount);
        long started = System.nanoTime();
        for (int i = 1; i <= clientCount; i++) {
            final int id = i;
            Thread worker = new Thread(() -> {
                try (ClientNetwork client = new ClientNetwork()) {
                    client.connect(host, port);
                    connected.incrementAndGet();
                    client.send(new Packet(PacketType.PING, "{\"pingId\":" + id + "}"));
                    Thread.sleep(holdMillis);
                } catch (IOException | RuntimeException e) {
                    failed.incrementAndGet();
                    System.err.println("[Benchmark] Client #" + id + " lỗi: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed.incrementAndGet();
                } finally { finished.countDown(); }
            }, "Benchmark-Client-" + id);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        boolean completed = finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        if (!completed) {
            for (Thread worker : workers) worker.interrupt();
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new Result(clientCount, connected.get(), failed.get() + (completed ? 0 : (int) finished.getCount()), elapsed);
    }
}

package client.network;

import common.protocol.Packet;
import common.protocol.PacketDecoder;
import common.protocol.PacketEncoder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import metrics.ThroughputMonitor;

/**
 * Socket client dùng chung cho Gameplay và các module khác.
 * Ví dụ: connect(host, port) → send(packet) → receive() → disconnect().
 * Việc đọc socket chạy trên thread nền để send() và receive() không tự quản lý socket.
 */
public final class ClientNetwork implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Consumer<Packet>> listeners = new CopyOnWriteArrayList<>();
    private final Map<Short, CopyOnWriteArrayList<Consumer<Packet>>> typedListeners = new ConcurrentHashMap<>();
    // Queue hỗ trợ module muốn pull packet; giới hạn dung lượng để không tăng RAM vô hạn.
    private final BlockingQueue<Packet> receivedPackets = new ArrayBlockingQueue<>(1_024);
    private final Object sendLock = new Object();
    private final ThroughputMonitor throughputMonitor = new ThroughputMonitor();
    private volatile Socket socket;
    private volatile Thread listenerThread;
    private volatile Consumer<Throwable> onDisconnect = error -> { };

    /** Tạo kết nối mới; timeout giúp tránh treo vô hạn khi host không phản hồi. */
    public synchronized void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port phải nằm trong 1..65535");
        if (running.get()) throw new IllegalStateException("Client đã được kết nối");
        Socket candidate = new Socket();
        try {
            candidate.connect(new InetSocketAddress(host, port), 5_000);
            candidate.setTcpNoDelay(true);
            receivedPackets.clear(); // Không để packet từ phiên trước lọt sang phiên mới.
            socket = candidate;
            running.set(true);
            Thread reader = new Thread(() -> listen(candidate), "Client-Network-Listener");
            reader.setDaemon(true); // Listener không giữ ứng dụng sống khi caller quên disconnect.
            listenerThread = reader;
            reader.start();
        } catch (IOException | RuntimeException e) {
            try { candidate.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    /** Gửi một packet; lock bảo vệ frame khỏi xen lẫn khi nhiều thread cùng gửi. */
    public void send(Packet packet) throws IOException {
        Objects.requireNonNull(packet, "packet");
        Socket active = socket;
        if (!running.get() || active == null || active.isClosed()) {
            throw new SocketException("Client chưa kết nối hoặc kết nối đã đóng");
        }
        synchronized (sendLock) {
            if (!running.get()) throw new SocketException("Kết nối đã đóng");
            // Ghi một frame nguyên vẹn để nhiều luồng gửi không làm xen byte trên TCP.
            byte[] frame = PacketEncoder.encode(packet);
            active.getOutputStream().write(frame);
            active.getOutputStream().flush();
            throughputMonitor.addPacket(frame.length);
        }
    }

    /**
     * Đăng ký listener toàn cục; callback chạy trên luồng đọc mạng.
     * Callback nên xử lý nhanh hoặc chuyển việc nặng/UI sang executor riêng.
     */
    public void addPacketListener(Consumer<Packet> listener) { listeners.add(Objects.requireNonNull(listener)); }
    public boolean removePacketListener(Consumer<Packet> listener) { return listeners.remove(listener); }

    /** Đăng ký listener chỉ cho một loại message; callback chạy trên luồng đọc mạng. */
    public void addPacketListener(short packetType, Consumer<Packet> listener) {
        typedListeners.computeIfAbsent(packetType, ignored -> new CopyOnWriteArrayList<>())
                .add(Objects.requireNonNull(listener));
    }

    /** Gỡ listener theo loại message; trả true khi listener đã được gỡ. */
    public boolean removePacketListener(short packetType, Consumer<Packet> listener) {
        CopyOnWriteArrayList<Consumer<Packet>> handlers = typedListeners.get(packetType);
        if (handlers == null) return false;
        boolean removed = handlers.remove(listener);
        if (handlers.isEmpty()) typedListeners.remove(packetType, handlers);
        return removed;
    }

    /**
     * Lấy packet kế tiếp từ hàng đợi; trả null nếu hết thời gian chờ.
     * Listener và hàng đợi đều nhận bản tham chiếu tới cùng Packet để hỗ trợ hai kiểu tích hợp.
     */
    public Packet receive(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Timeout không thể âm");
        return receivedPackets.poll(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Chờ packet kế tiếp. Trả về null nếu kết nối bị ngắt trước khi có packet mới.
     * Dùng overload receive(Duration) khi module cần tự đặt thời hạn chờ.
     */
    public Packet receive() throws InterruptedException {
        while (true) {
            Packet packet = receivedPackets.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (packet != null) return packet;
            if (!running.get()) return null;
        }
    }

    /** Số packet đang đợi consumer lấy qua receive(). */
    public int getQueuedPacketCount() { return receivedPackets.size(); }

    /** Bộ đếm tổng traffic gửi + nhận để benchmark hoặc giao diện debug sử dụng. */
    public ThroughputMonitor getThroughputMonitor() { return throughputMonitor; }

    /** API tương thích với ví dụ trong đề: chỉ giữ một listener chính. */
    public void setOnPacketReceived(Consumer<Packet> listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    public void setOnDisconnect(Consumer<Throwable> listener) {
        onDisconnect = listener == null ? error -> { } : listener;
    }

    public boolean isConnected() {
        Socket active = socket;
        return running.get() && active != null && active.isConnected() && !active.isClosed();
    }

    private void listen(Socket active) {
        Throwable disconnectCause = null;
        try {
            while (running.get() && !active.isClosed()) {
                Packet packet = PacketDecoder.decode(active.getInputStream());
                int frameSize = Integer.BYTES + Short.BYTES
                        + packet.getJsonPayload().getBytes(StandardCharsets.UTF_8).length;
                throughputMonitor.addPacket(frameSize);
                if (!receivedPackets.offer(packet)) {
                    System.err.println("[ClientNetwork] Hàng đợi nhận đầy; packet "
                            + String.format("0x%04X", Short.toUnsignedInt(packet.getType())) + " bị bỏ qua ở hàng đợi.");
                }
                notifyListeners(listeners, packet);
                notifyListeners(typedListeners.get(packet.getType()), packet);
            }
        } catch (IOException e) {
            if (running.get()) disconnectCause = e;
        } finally {
            // Cùng khóa với connect/disconnect để reader cũ không làm ngắt phiên reconnect.
            boolean wasRunning;
            synchronized (this) {
                wasRunning = socket == active && running.getAndSet(false);
                if (wasRunning) socket = null;
            }
            try { active.close(); } catch (IOException ignored) { /* Socket đã mất; dọn dẹp best-effort. */ }
            if (wasRunning) {
                try { onDisconnect.accept(disconnectCause); }
                catch (RuntimeException e) { System.err.println("[ClientNetwork] Disconnect listener lỗi: " + e.getMessage()); }
            }
        }
    }

    private void notifyListeners(Iterable<Consumer<Packet>> packetListeners, Packet packet) {
        if (packetListeners == null) return;
        for (Consumer<Packet> listener : packetListeners) {
            try { listener.accept(packet); }
            catch (RuntimeException e) {
                System.err.println("[ClientNetwork] Listener lỗi: " + e.getMessage());
            }
        }
    }

    /** Đóng socket sẽ đánh thức thread đọc đang block và kết thúc listener. */
    public synchronized void disconnect() {
        running.set(false);
        Socket active = socket;
        socket = null;
        if (active != null) {
            try { active.close(); }
            catch (IOException e) { System.err.println("[ClientNetwork] Lỗi đóng socket: " + e.getMessage()); }
        }
        listenerThread = null;
        receivedPackets.clear();
    }

    @Override public void close() { disconnect(); }
}

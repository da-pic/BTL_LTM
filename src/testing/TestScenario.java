package testing;

import client.network.ClientNetwork;
import common.protocol.Packet;
import common.protocol.PacketDecoder;
import common.protocol.PacketEncoder;
import common.protocol.PacketType;
import common.router.MessageRouter;
import metrics.LatencyMonitor;
import metrics.ThroughputMonitor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Kịch bản kiểm tra protocol, route, metrics và luồng socket loopback cơ bản. */
public final class TestScenario {
    private TestScenario() { }

    public static void runBasicProtocolTest() throws Exception {
        NetworkTest.runAll();
        System.out.println("[TestScenario] Đã hoàn tất các kiểm tra protocol/network cơ bản.");
    }

    /** Kiểm tra end-to-end một client gửi packet và nhận phản hồi qua TCP loopback. */
    public static void runLoopbackTest() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    Packet incoming = PacketDecoder.decode(socket.getInputStream());
                    if (incoming.getType() != PacketType.PING) throw new AssertionError("Server nhận sai loại packet");
                    PacketEncoder.encode(new Packet(PacketType.PONG, incoming.getJsonPayload()), socket.getOutputStream());
                    received.countDown();
                } catch (Throwable e) { serverFailure.set(e); received.countDown(); }
            }, "Loopback-Test-Server");
            serverThread.setDaemon(true);
            serverThread.start();

            CountDownLatch pong = new CountDownLatch(1);
            AtomicInteger typedPongCount = new AtomicInteger();
            try (ClientNetwork client = new ClientNetwork()) {
                client.addPacketListener(PacketType.PONG, packet -> {
                    typedPongCount.incrementAndGet();
                    pong.countDown();
                });
                client.connect("127.0.0.1", server.getLocalPort());
                client.send(new Packet(PacketType.PING, "{\"pingId\":1}"));
                if (!pong.await(3, TimeUnit.SECONDS)) throw new AssertionError("Không nhận PONG trong 3 giây");
                Packet queued = client.receive();
                if (queued == null || queued.getType() != PacketType.PONG) {
                    throw new AssertionError("API receive() không trả packet nhận được");
                }
                if (typedPongCount.get() != 1) throw new AssertionError("Listener theo packet type không được gọi đúng");
                if (client.getThroughputMonitor().getTotalPackets() != 2) {
                    throw new AssertionError("ClientNetwork chưa đếm cả packet gửi và nhận");
                }
            }
            if (!received.await(1, TimeUnit.SECONDS)) throw new AssertionError("Server chưa xử lý packet");
            if (serverFailure.get() != null) throw new AssertionError("Loopback server lỗi", serverFailure.get());
        }
    }

    /** Các assertion nhỏ dùng được không cần framework test bên ngoài. */
    static void assertCodecRoundTrip() throws IOException {
        Packet expected = new Packet(PacketType.CHAT, "{\"text\":\"Xin chào 🌟\"}");
        byte[] frame = PacketEncoder.encode(expected);
        Packet actual = PacketDecoder.decode(new ByteArrayInputStream(frame));
        if (!expected.equals(actual)) throw new AssertionError("Codec round-trip làm sai packet");

        // Hai frame liền nhau xác nhận decoder dừng đúng ranh giới từng message TCP.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PacketEncoder.encode(expected, stream);
        PacketEncoder.encode(new Packet(PacketType.PING, "{}"), stream);
        ByteArrayInputStream input = new ByteArrayInputStream(stream.toByteArray());
        if (!expected.equals(PacketDecoder.decode(input))) throw new AssertionError("Frame thứ nhất bị sai");
        if (PacketDecoder.decode(input).getType() != PacketType.PING) throw new AssertionError("Frame thứ hai bị dính");
    }

    static void assertRouter() {
        MessageRouter router = new MessageRouter(message -> { throw new AssertionError(message); });
        AtomicReference<String> routedId = new AtomicReference<>();
        router.registerHandler(PacketType.MOVE, (clientId, packet) -> routedId.set(clientId + ":" + packet.getJsonPayload()));
        if (!router.route("player-1", new Packet(PacketType.MOVE, "{}"))) throw new AssertionError("Route thất bại");
        if (!"player-1:{}".equals(routedId.get())) throw new AssertionError("Router giao nhầm handler");
        if (!router.unregisterHandler(PacketType.MOVE)) throw new AssertionError("Unregister handler thất bại");
    }

    static void assertMetrics() {
        LatencyMonitor latency = new LatencyMonitor();
        latency.recordSampleNanos(10_000_000); latency.recordSampleNanos(20_000_000);
        latency.recordSampleNanos(30_000_000);
        if (Math.abs(latency.getAverageRTT() - 20.0) > 0.001 || latency.getP95RTT() != 30.0) {
            throw new AssertionError("Latency statistics sai");
        }
        ThroughputMonitor throughput = new ThroughputMonitor();
        throughput.addPacket(10); throughput.addPacket(20);
        if (throughput.getTotalPackets() != 2 || throughput.getTotalBytes() != 30) {
            throw new AssertionError("Throughput counters sai");
        }
    }

    static void assertInvalidLengthRejected() throws IOException {
        byte[] badLength = new byte[] {0, 0, 0, 1};
        try {
            PacketDecoder.decode(new ByteArrayInputStream(badLength));
            throw new AssertionError("Decoder phải từ chối length nhỏ hơn 2");
        } catch (common.protocol.ProtocolException expected) { /* Đúng: length không chứa đủ type. */ }
    }

}

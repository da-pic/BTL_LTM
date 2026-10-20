import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TestClient {
    private static final short TYPE_PING = 0x0040;
    private static final short TYPE_PONG = 0x0041;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final List<Client> clients = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 8888;
        int clientCount = 1;
        int pingSeconds = 3;
        int durationSeconds = 0;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--clients" -> clientCount = Integer.parseInt(args[++i]);
                case "--ping" -> pingSeconds = Integer.parseInt(args[++i]);
                case "--duration" -> durationSeconds = Integer.parseInt(args[++i]);
                case "--help" -> {
                    usage();
                    return;
                }
                default -> {
                    System.out.println("Unknown option: " + args[i]);
                    usage();
                    return;
                }
            }
        }

        System.out.println("TestClient -> " + host + ":" + port
                + " | clients=" + clientCount
                + " | ping=" + (pingSeconds > 0 ? pingSeconds + "s" : "off (idle drop demo)")
                + " | duration=" + (durationSeconds > 0 ? durationSeconds + "s" : "until Ctrl+C"));
        System.out.println("note: PING bytes keep the session alive now; PONG and room/chat frames"
                + " appear once Giang's router is wired.");

        CountDownLatch done = new CountDownLatch(clientCount);
        for (int i = 1; i <= clientCount; i++) {
            Client client = new Client(i, host, port, pingSeconds, done);
            clients.add(client);
            new Thread(client, "client-" + i).start();
        }

        if (durationSeconds > 0) {
            Thread.sleep(durationSeconds * 1000L);
            System.out.println("duration reached, closing " + clientCount + " socket(s) abruptly");
            for (Client client : clients) {
                client.abort();
            }
        }
        done.await();
        System.out.println("all clients finished");
    }

    private static void usage() {
        System.out.println("""
                Usage: java TestClient.java [options]
                  --host <host>         server host (default localhost)
                  --port <port>         server port (default 8888)
                  --clients <n>         number of concurrent clients (default 1)
                  --ping <seconds>      send a framed PING every N seconds; 0 = idle mode (default 3)
                  --duration <seconds>  run N seconds then close abruptly; 0 = until Ctrl+C (default 0)

                Examples:
                  java TestClient.java --port 8888 --ping 0
                  java TestClient.java --port 8888 --clients 4 --ping 2 --duration 30
                """);
    }

    private static final class Client implements Runnable {
        private final int id;
        private final String host;
        private final int port;
        private final int pingSeconds;
        private final CountDownLatch done;
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger pingsSent = new AtomicInteger();
        private final AtomicInteger framesReceived = new AtomicInteger();
        private final AtomicLong lastRttMillis = new AtomicLong(-1);

        private volatile Socket socket;
        private volatile boolean closing;

        Client(int id, String host, int port, int pingSeconds, CountDownLatch done) {
            this.id = id;
            this.host = host;
            this.port = port;
            this.pingSeconds = pingSeconds;
            this.done = done;
        }

        @Override
        public void run() {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 3000);
                log("connected");
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                Thread reader = new Thread(() -> readLoop(in), "reader-" + id);
                reader.setDaemon(true);
                reader.start();

                if (pingSeconds <= 0) {
                    log("idle mode: sending nothing, expect server drop after ~10s");
                    reader.join(30_000);
                    if (reader.isAlive()) {
                        log("no server drop observed within 30s (check heartbeat config)");
                    }
                    return;
                }

                while (true) {
                    long sentAt = System.nanoTime();
                    out.writeInt(Short.BYTES + Long.BYTES);
                    out.writeShort(TYPE_PING);
                    out.writeLong(sentAt);
                    out.flush();
                    log("sent PING #" + pingsSent.incrementAndGet());
                    Thread.sleep(pingSeconds * 1000L);
                }
            } catch (IOException error) {
                log("connection error: " + error.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                closeSocket();
                log("finished after " + elapsedSeconds() + "s | pings=" + pingsSent.get()
                        + " framesReceived=" + framesReceived.get()
                        + (lastRttMillis.get() >= 0 ? " lastRtt=" + lastRttMillis.get() + "ms" : ""));
                done.countDown();
            }
        }

        private void readLoop(DataInputStream in) {
            try {
                while (true) {
                    int length = in.readInt();
                    short type = in.readShort();
                    byte[] payload = new byte[length - Short.BYTES];
                    in.readFully(payload);
                    framesReceived.incrementAndGet();
                    handleFrame(type, payload);
                }
            } catch (EOFException eof) {
                if (!closing) {
                    log("server closed connection after " + elapsedSeconds() + "s");
                }
            } catch (SocketException reset) {
                if (!closing) {
                    log("connection reset by server after " + elapsedSeconds() + "s");
                }
            } catch (IOException error) {
                if (!closing) {
                    log("read error: " + error.getMessage());
                }
            }
        }

        private void handleFrame(short type, byte[] payload) {
            if (type == TYPE_PONG && payload.length >= Long.BYTES) {
                long rttMillis = (System.nanoTime() - ByteBuffer.wrap(payload).getLong()) / 1_000_000;
                lastRttMillis.set(rttMillis);
                log("received PONG rtt=" + rttMillis + "ms");
            } else {
                log("received frame type=0x" + String.format("%04X", type)
                        + " payload=" + payload.length + "B");
            }
        }

        void abort() {
            closing = true;
            closeSocket();
        }

        private void closeSocket() {
            Socket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Best effort during test shutdown.
                }
            }
        }

        private long elapsedSeconds() {
            return (System.nanoTime() - startedNanos) / 1_000_000_000;
        }

        private void log(String message) {
            System.out.println("[" + LocalTime.now().format(TIME) + "][client-" + id + "] " + message);
        }
    }
}

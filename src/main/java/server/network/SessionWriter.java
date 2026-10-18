package server.network;

import server.session.Session;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class SessionWriter implements OutboundSender, AutoCloseable {
    private final Session session;
    private final BlockingQueue<OutboundMessage> queue;
    private final Thread worker;
    private volatile boolean running = true;

    public SessionWriter(Session session, int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.session = session;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::run, "session-writer-" + session.getPlayerId());
        session.setOutboundSender(this);
        this.worker.start();
    }

    @Override
    public void send(Session target, OutboundMessage message) {
        if (target != session) {
            throw new IllegalArgumentException("Writer belongs to a different session");
        }
        if (!running || !queue.offer(message)) {
            throw new IllegalStateException("Outbound queue is closed or full");
        }
    }

    private void run() {
        while (running) {
            try {
                OutboundMessage message = queue.take();
                Socket socket = session.getSocket();
                if (socket == null || socket.isClosed()) {
                    continue;
                }
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                byte[] payload = message.payload();
                output.writeInt(Short.BYTES + payload.length);
                output.writeShort(message.type());
                output.write(payload);
                output.flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException error) {
                running = false;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}

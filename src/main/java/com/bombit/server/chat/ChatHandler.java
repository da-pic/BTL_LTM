package com.bombit.server.chat;

import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import com.bombit.server.room.Room;
import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class ChatHandler implements AutoCloseable {
    public static final short CHAT_MESSAGE_TYPE = 0x0050;

    private final RoomManager roomManager;
    private final OutboundSender sender;
    private final BlockingQueue<ChatMessage> queue;
    private final Thread worker;
    private volatile boolean running = true;

    public ChatHandler(RoomManager roomManager, OutboundSender sender, int queueCapacity) {
        this.roomManager = roomManager;
        this.sender = sender;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.worker = new Thread(this::run, "chat-worker");
        this.worker.start();
    }

    public boolean enqueue(ChatMessage message) {
        return running && queue.offer(message);
    }

    private void run() {
        while (running) {
            try {
                ChatMessage message = queue.take();
                Room room = roomManager.find(message.roomId());
                if (room == null) {
                    continue;
                }
                byte[] payload = ("[" + message.senderName() + "]: " + message.message())
                        .getBytes(StandardCharsets.UTF_8);
                for (Session session : room.getPlayersSnapshot()) {
                    if (session.getState() != SessionState.DISCONNECTED
                            && session.getState() != SessionState.CLOSED
                            && session.getOutboundSender() != null) {
                        sender.send(session, new OutboundMessage(CHAT_MESSAGE_TYPE, payload));
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}

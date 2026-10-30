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
    private final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
    public static final short CHAT_MESSAGE_TYPE = com.bombit.common.protocol.PacketType.CHAT;

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

    public synchronized boolean enqueue(ChatMessage message) {
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
                if (message.senderId() == null || room.getPlayersSnapshot().stream().noneMatch(p ->
                        p.getPlayerId().equals(message.senderId()) && p.getState() != SessionState.DISCONNECTED
                                && p.getState() != SessionState.CLOSED)) continue;
                var packet = room.configuration().isPresent()?com.bombit.common.protocol.JsonV3.packet(CHAT_MESSAGE_TYPE,
                        com.bombit.common.protocol.JsonV3.body("roomId",message.roomId(),"sessionId",message.senderId(),"username",message.senderName(),"message",message.message())):
                        com.bombit.common.protocol.LobbyPacketCodec.chat(
                        new com.bombit.common.protocol.LobbyPacketCodec.Chat(message.roomId(), message.senderId(),
                                message.senderName(), message.message()));
                for (Session session : room.getPlayersSnapshot()) {
                    if (session.getState() != SessionState.DISCONNECTED
                            && session.getState() != SessionState.CLOSED
                            && session.getOutboundSender() != null) {
                        try{sender.send(session,OutboundMessage.fromPacket(packet));}
                        catch(RuntimeException e){failure.compareAndSet(null,e);}
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch(RuntimeException e){failure.compareAndSet(null,e);
            }
        }
    }

    @Override
    public void close() {
        synchronized(this){running=false;queue.clear();}
        worker.interrupt();
        if (Thread.currentThread() != worker) try { worker.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    public boolean isAlive(){return worker.isAlive();}
    public int pendingMessages(){return queue.size();}
    public java.util.Optional<Throwable> failure(){return java.util.Optional.ofNullable(failure.get());}
}

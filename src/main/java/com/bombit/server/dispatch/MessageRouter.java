package com.bombit.server.dispatch;

import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.PacketType;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Dispatcher dạng bảng tra cứu, an toàn khi nhiều ClientHandler cùng route message. */
public final class MessageRouter {
    private final ConcurrentMap<Short, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final Consumer<String> unknownTypeListener;

    public MessageRouter() { this(message -> System.err.println("[MessageRouter] " + message)); }
    public MessageRouter(Consumer<String> unknownTypeListener) {
        this.unknownTypeListener = Objects.requireNonNull(unknownTypeListener, "unknownTypeListener");
    }

    /** Đăng ký/thay thế handler của một loại packet. */
    public void registerHandler(short packetType, MessageHandler handler) {
        handlers.put(packetType, Objects.requireNonNull(handler, "handler"));
    }

    public boolean unregisterHandler(short packetType) { return handlers.remove(packetType) != null; }
    public boolean hasHandler(short packetType) { return handlers.containsKey(packetType); }

    /** Trả true khi đã chuyển được packet tới handler. */
    public boolean route(String clientId, Packet packet) {
        Objects.requireNonNull(packet, "packet");
        MessageHandler handler = handlers.get(packet.getType());
        if (handler == null) {
            unknownTypeListener.accept("Không tìm thấy Handler cho " + PacketType.nameOf(packet.getType())
                    + " (0x" + String.format("%04X", Short.toUnsignedInt(packet.getType())) + ")");
            return false;
        }
        handler.handle(clientId, packet);
        return true;
    }
}

package com.bombit.server.dispatch;

import com.bombit.common.protocol.*;
import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;
import com.bombit.server.session.SessionState;

/** Only session transport handlers. Gameplay/room handlers are registered in later steps. */
public final class SessionHandlers {
    private SessionHandlers() { }

    public static void register(MessageRouter router, SessionManager sessions, OutboundSender sender) {
        register(router, sessions, sender, session -> { });
    }
    public static void register(MessageRouter router, SessionManager sessions, OutboundSender sender,
                                java.util.function.Consumer<Session> connected) {
        router.registerHandler(PacketType.CONNECT, (id, packet) -> {
            Session session = sessions.find(id).orElseThrow();
            var connect = SessionPacketCodec.connect(packet);
            if (connect.protocolVersion() != IntegrationContract.PROTOCOL_VERSION) {
                throw new UnsupportedVersionException();
            }
            session.completeHandshake(connect.playerName());
            sender.send(session, OutboundMessage.fromPacket(SessionPacketCodec.acknowledgement(id, session.getReconnectToken())));
            connected.accept(session);
        });
        router.registerHandler(PacketType.PING, (id, packet) -> {
            SessionPacketCodec.pingId(packet);
            Session session = sessions.find(id).orElseThrow();
            session.updateHeartbeat();
            sender.send(session, OutboundMessage.fromPacket(new Packet(PacketType.PONG, packet.getJsonPayload())));
        });
        router.registerHandler(PacketType.DISCONNECT, (id, packet) -> {
            SessionPacketCodec.disconnect(packet);
            // ClientHandler drains the writer and removes the session after this handler returns.
        });
    }

    public static final class UnsupportedVersionException extends IllegalArgumentException { }
}

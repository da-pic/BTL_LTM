package server.network;

import server.session.Session;

public interface OutboundSender {
    void send(Session session, OutboundMessage message);
}

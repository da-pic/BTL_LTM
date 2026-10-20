package com.bombit.server.network;

import com.bombit.server.session.Session;

public interface OutboundSender {
    void send(Session session, OutboundMessage message);
}

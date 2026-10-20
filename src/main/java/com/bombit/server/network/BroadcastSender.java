package com.bombit.server.network;

import com.bombit.server.session.Session;

public final class BroadcastSender implements OutboundSender {
    @Override
    public void send(Session session, OutboundMessage message) {
        OutboundSender target = session.getOutboundSender();
        if (target == null) {
            return;
        }
        try {
            target.send(session, message);
        } catch (IllegalStateException closedOrFull) {
            // Session closed or queue full: skip so broadcast never blocks the caller.
        }
    }
}

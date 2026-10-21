package com.bombit.server.integration;

import com.bombit.server.session.Session;

public interface SessionLifecycleListener {
    void onPlayerDisconnected(Session session);
    void onPlayerReconnected(Session session);
    void onPlayerHardDropped(Session session);
}

package com.bombit.server.integration;

import com.bombit.server.session.Session;

public final class NoOpSessionLifecycleListener implements SessionLifecycleListener {
    @Override public void onPlayerDisconnected(Session session) {}
    @Override public void onPlayerReconnected(Session session) {}
    @Override public void onPlayerHardDropped(Session session) {}
}

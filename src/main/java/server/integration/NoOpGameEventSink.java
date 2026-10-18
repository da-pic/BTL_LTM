package server.integration;

import server.session.Session;

public final class NoOpGameEventSink implements GameEventSink {
    @Override public void onPlayerDisconnected(Session session) {}
    @Override public void onPlayerReconnected(Session session) {}
    @Override public void onPlayerHardDropped(Session session) {}
}

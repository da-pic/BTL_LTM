package server.integration;

import server.session.Session;

public interface GameEventSink {
    void onPlayerDisconnected(Session session);
    void onPlayerReconnected(Session session);
    void onPlayerHardDropped(Session session);
}

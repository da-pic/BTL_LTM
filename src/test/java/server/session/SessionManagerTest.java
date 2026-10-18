package server.session;

import org.junit.jupiter.api.Test;
import server.integration.GameEventSink;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @Test
    void reconnectsBeforeGracePeriodAndRejectsWrongToken() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        GameEventSink sink = new GameEventSink() {
            public void onPlayerDisconnected(Session session) {}
            public void onPlayerReconnected(Session session) { reconnects.incrementAndGet(); }
            public void onPlayerHardDropped(Session session) {}
        };
        try (SessionManager manager = new SessionManager(sink, 2)) {
            Session session = new Session("mai", new Socket());
            manager.register(session);
            manager.handleUnexpectedDisconnect(session.getPlayerId());
            assertFalse(manager.reconnect(session.getPlayerId(), "wrong", new Socket()));
            assertTrue(manager.reconnect(session.getPlayerId(), session.getReconnectToken(), new Socket()));
            assertEquals(SessionState.CONNECTED, session.getState());
            assertEquals(1, reconnects.get());
        }
    }

    @Test
    void restoresPreDisconnectStateWhenPlaying() throws Exception {
        GameEventSink sink = new GameEventSink() {
            public void onPlayerDisconnected(Session session) {}
            public void onPlayerReconnected(Session session) {}
            public void onPlayerHardDropped(Session session) {}
        };
        try (SessionManager manager = new SessionManager(sink, 2)) {
            Session session = new Session("mai", new Socket());
            manager.register(session);
            session.setState(SessionState.PLAYING);
            manager.handleUnexpectedDisconnect(session.getPlayerId());
            assertTrue(manager.reconnect(session.getPlayerId(), session.getReconnectToken(), new Socket()));
            assertEquals(SessionState.PLAYING, session.getState());
        }
    }
}

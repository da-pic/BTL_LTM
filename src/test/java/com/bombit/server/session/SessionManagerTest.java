package com.bombit.server.session;

import org.junit.jupiter.api.Test;
import com.bombit.server.integration.NoOpSessionLifecycleListener;
import com.bombit.server.integration.SessionLifecycleListener;

import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    @Test
    void createsAndFindsSession() {
        NoOpSessionLifecycleListener listener = new NoOpSessionLifecycleListener();
        try (SessionManager manager = new SessionManager(listener, 10)) {
            Session session = new Session("player1", new Socket());
            manager.register(session);

            Optional<Session> found = manager.find(session.getPlayerId());
            assertTrue(found.isPresent());
            assertEquals("player1", found.get().getPlayerName());
            assertEquals(1, manager.snapshot().size());

            // Non-existent ID returns empty
            assertFalse(manager.find("unknown-id").isPresent());
            assertFalse(manager.find(null).isPresent());
        }
    }

    @Test
    void duplicateRegistrationThrowsException() {
        NoOpSessionLifecycleListener listener = new NoOpSessionLifecycleListener();
        try (SessionManager manager = new SessionManager(listener, 10)) {
            Session session = new Session("player1", new Socket());
            manager.register(session);
            assertThrows(IllegalStateException.class, () -> manager.register(session));
        }
    }

    @Test
    void reconnectsBeforeGracePeriodAndRestoresState() throws Exception {
        AtomicInteger reconnects = new AtomicInteger();
        SessionLifecycleListener listener = new SessionLifecycleListener() {
            public void onPlayerDisconnected(Session session) {}
            public void onPlayerReconnected(Session session) { reconnects.incrementAndGet(); }
            public void onPlayerHardDropped(Session session) {}
        };
        try (SessionManager manager = new SessionManager(listener, 2)) {
            Session session = new Session("mai", new Socket());
            manager.register(session);
            session.setState(SessionState.PLAYING);

            manager.handleUnexpectedDisconnect(session.getPlayerId());
            assertEquals(SessionState.DISCONNECTED, session.getState());

            Socket newSocket = new Socket();
            assertTrue(manager.reconnect(session.getPlayerId(), session.getReconnectToken(), newSocket));
            assertEquals(SessionState.PLAYING, session.getState());
            assertEquals(1, reconnects.get());
        }
    }

    @Test
    void rejectsWrongTokenOnReconnect() throws Exception {
        NoOpSessionLifecycleListener listener = new NoOpSessionLifecycleListener();
        try (SessionManager manager = new SessionManager(listener, 2)) {
            Session session = new Session("mai", new Socket());
            manager.register(session);
            manager.handleUnexpectedDisconnect(session.getPlayerId());

            Socket wrongSocket = new Socket();
            assertFalse(manager.reconnect(session.getPlayerId(), "invalid-token", wrongSocket));
            assertTrue(wrongSocket.isClosed());
            assertEquals(SessionState.DISCONNECTED, session.getState());
        }
    }

    @Test
    void gracefulDisconnectClosesImmediately() {
        AtomicInteger hardDrops = new AtomicInteger();
        SessionLifecycleListener listener = new SessionLifecycleListener() {
            public void onPlayerDisconnected(Session session) {}
            public void onPlayerReconnected(Session session) {}
            public void onPlayerHardDropped(Session session) { hardDrops.incrementAndGet(); }
        };
        try (SessionManager manager = new SessionManager(listener, 10)) {
            Session session = new Session("mai", new Socket());
            manager.register(session);

            manager.disconnect(session.getPlayerId());
            assertEquals(SessionState.CLOSED, session.getState());
            assertEquals(1, hardDrops.get());
            assertFalse(manager.find(session.getPlayerId()).isPresent());
        }
    }
}

package com.bombit.server.session;

import org.junit.jupiter.api.Test;
import com.bombit.server.integration.GameEventSink;

import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    @Test
    void disconnectRacingShutdownDoesNotScheduleAfterExecutorClose() throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        GameEventSink sink = new GameEventSink() {
            public void onPlayerDisconnected(Session session) {
                entered.countDown();
                try { if (!resume.await(2, TimeUnit.SECONDS)) throw new AssertionError("Missing shutdown barrier"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            public void onPlayerReconnected(Session session) { }
            public void onPlayerHardDropped(Session session) { }
        };
        var pool = Executors.newSingleThreadExecutor();
        try (var manager = new SessionManager(sink, 2); var socket = new Socket()) {
            var session = new Session("shutdown", socket);
            manager.register(session);
            var task = pool.submit(() -> manager.handleUnexpectedDisconnect(session.getPlayerId()));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                manager.close();
            } finally { resume.countDown(); }
            task.get(2, TimeUnit.SECONDS);
            assertTrue(manager.snapshot().isEmpty());
            assertEquals(SessionState.CLOSED, session.getState());
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

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

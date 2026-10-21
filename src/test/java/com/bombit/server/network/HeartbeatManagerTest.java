package com.bombit.server.network;

import org.junit.jupiter.api.Test;
import com.bombit.common.protocol.PacketType;
import com.bombit.server.heartbeat.HeartbeatManager;
import com.bombit.server.integration.SessionLifecycleListener;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;
import com.bombit.server.session.SessionState;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatManagerTest {

    @Test
    void onPingSendsPongAndUpdatesHeartbeat() {
        AtomicReference<OutboundMessage> sent = new AtomicReference<>();
        OutboundSender testSender = (session, message) -> sent.set(message);

        SessionLifecycleListener listener = new SessionLifecycleListener() {
            public void onPlayerDisconnected(Session s) {}
            public void onPlayerReconnected(Session s) {}
            public void onPlayerHardDropped(Session s) {}
        };

        try (SessionManager sessionManager = new SessionManager(listener, 10);
             HeartbeatManager heartbeatManager = new HeartbeatManager(sessionManager, 10, TimeUnit.SECONDS, testSender)) {

            Session session = new Session("player1", new Socket());
            byte[] pingPayload = "{\"pingId\":123}".getBytes(StandardCharsets.UTF_8);

            heartbeatManager.onPing(session, pingPayload);

            assertNotNull(sent.get());
            assertEquals(PacketType.PONG, sent.get().type());
            assertEquals("{\"pingId\":123}", new String(sent.get().payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void timesOutIdleSessionAndTriggersSoftDrop() throws Exception {
        AtomicInteger disconnected = new AtomicInteger();
        SessionLifecycleListener listener = new SessionLifecycleListener() {
            public void onPlayerDisconnected(Session s) { disconnected.incrementAndGet(); }
            public void onPlayerReconnected(Session s) {}
            public void onPlayerHardDropped(Session s) {}
        };

        OutboundSender testSender = (session, message) -> {};

        // Short timeout: 100 milliseconds
        try (SessionManager sessionManager = new SessionManager(listener, 2);
             HeartbeatManager heartbeatManager = new HeartbeatManager(sessionManager, 100, TimeUnit.MILLISECONDS, testSender)) {

            Session session = new Session("idle-player", new Socket());
            sessionManager.register(session);
            assertEquals(SessionState.CONNECTED, session.getState());

            // Wait for timeout to expire
            Thread.sleep(150);

            // Trigger sweep
            heartbeatManager.sweep();

            // Session must now be disconnected
            assertEquals(SessionState.DISCONNECTED, session.getState());
            assertEquals(1, disconnected.get());
        }
    }
}

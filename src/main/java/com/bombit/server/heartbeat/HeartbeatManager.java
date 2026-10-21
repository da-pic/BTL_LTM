package com.bombit.server.heartbeat;

import com.bombit.common.protocol.PacketType;
import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;
import com.bombit.server.session.SessionState;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HeartbeatManager implements AutoCloseable {
    public static final short PONG_TYPE = PacketType.PONG;

    private static final long SWEEP_INTERVAL_SECONDS = 2;
    private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private final SessionManager sessionManager;
    private final OutboundSender sender;
    private final long timeoutNanos;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public HeartbeatManager(SessionManager sessionManager, long timeout, TimeUnit unit, OutboundSender sender) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.sessionManager = sessionManager;
        this.sender = sender;
        this.timeoutNanos = unit.toNanos(timeout);
        scheduler.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void onPing(Session session, byte[] pingPayload) {
        if (session == null) {
            return;
        }
        session.updateHeartbeat();
        byte[] pongPayload = pingPayload == null || pingPayload.length == 0
                ? EMPTY_JSON
                : pingPayload.clone();
        sender.send(session, new OutboundMessage(PONG_TYPE, pongPayload));
    }

    public void sweep() {
        for (Session session : sessionManager.snapshot().values()) {
            if (session.getState() != SessionState.DISCONNECTED
                    && session.getState() != SessionState.CLOSED
                    && session.isHeartbeatExpired(timeoutNanos)) {
                sessionManager.handleUnexpectedDisconnect(session.getPlayerId());
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

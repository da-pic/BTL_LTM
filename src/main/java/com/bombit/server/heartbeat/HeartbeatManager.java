package com.bombit.server.heartbeat;

import com.bombit.server.network.OutboundMessage;
import com.bombit.server.network.OutboundSender;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;
import com.bombit.server.session.SessionState;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class HeartbeatManager implements AutoCloseable {
    public static final short PONG_TYPE = 0x0041;

    private static final long SWEEP_INTERVAL_SECONDS = 2;
    private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private final SessionManager sessionManager;
    private final OutboundSender sender;
    private final long timeoutNanos;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"heartbeat-sweep"));
    private final AtomicReference<Throwable> failure=new AtomicReference<>();

    public HeartbeatManager(SessionManager sessionManager, long timeout, TimeUnit unit, OutboundSender sender) {
        this(sessionManager,timeout,unit,sender,TimeUnit.SECONDS.toMillis(SWEEP_INTERVAL_SECONDS));
    }
    public HeartbeatManager(SessionManager sessionManager,long timeout,TimeUnit unit,OutboundSender sender,long sweepMillis){
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.sessionManager = sessionManager;
        this.sender = sender;
        this.timeoutNanos = unit.toNanos(timeout);
        if(sweepMillis<=0)throw new IllegalArgumentException("Invalid sweep interval");
        scheduler.scheduleAtFixedRate(this::sweep,sweepMillis,sweepMillis,TimeUnit.MILLISECONDS);
    }

    public void onPing(Session session, byte[] pingPayload) {
        session.updateHeartbeat();
        byte[] pongPayload = pingPayload == null || pingPayload.length == 0
                ? EMPTY_JSON
                : pingPayload.clone();
        sender.send(session, new OutboundMessage(PONG_TYPE, pongPayload));
    }

    private void sweep() {
        for (Session session : sessionManager.snapshot().values()) {
            try{if (session.getState() != SessionState.DISCONNECTED && session.getState()!=SessionState.CLOSED
                    && session.isHeartbeatExpired(timeoutNanos)) {
                if(session.getState()==SessionState.CONNECTED)sessionManager.hardDrop(session.getPlayerId());
                else sessionManager.handleUnexpectedDisconnect(session.getPlayerId());
            }}catch(RuntimeException e){failure.compareAndSet(null,e);}
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try{scheduler.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    public boolean isTerminated(){return scheduler.isTerminated();}
    public Optional<Throwable> failure(){return Optional.ofNullable(failure.get());}
}

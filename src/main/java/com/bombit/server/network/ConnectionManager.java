package com.bombit.server.network;

import com.bombit.server.session.Session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.net.Socket;
import java.util.function.BiConsumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class ConnectionManager implements AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY = 256;

    private final Map<String, SessionWriter> writers = new ConcurrentHashMap<>();
    private volatile BiConsumer<Session, Socket> failureHandler = (session, socket) -> { };
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"connection-watchdog"));
    private final ThreadPoolExecutor cleanup=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->new Thread(r,"connection-cleanup"));
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private final int capacity;
    private final long writeTimeoutNanos;
    private boolean closed;
    public ConnectionManager(){this(DEFAULT_QUEUE_CAPACITY,5000);}
    public ConnectionManager(int capacity,long writeTimeoutMillis){
        if(capacity<=0||writeTimeoutMillis<=0)throw new IllegalArgumentException("Invalid writer config");
        this.capacity=capacity;writeTimeoutNanos=TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis);
        watchdog.scheduleAtFixedRate(()->{long now=System.nanoTime();for(var writer:writers.values())writer.expireWrite(now,writeTimeoutNanos);},25,25,TimeUnit.MILLISECONDS);
    }

    public void setFailureHandler(BiConsumer<Session, Socket> handler) {
        failureHandler = java.util.Objects.requireNonNull(handler);
    }

    public synchronized SessionWriter open(Session session) {
        if(closed)throw new IllegalStateException("Connections closed");
        if (writers.containsKey(session.getPlayerId())) throw new IllegalStateException("Writer already exists");
        Socket socket = session.getSocket();
        SessionWriter writer = new SessionWriter(session, capacity, () -> {
            // Never invoke lobby/session callbacks while a producer holds a room/lifecycle lock.
            try { cleanup.execute(()->{
                try { failureHandler.accept(session,socket); }
                catch(RuntimeException e){failure.compareAndSet(null,e);}
                finally { release(session.getPlayerId(),socket); }
            }); } catch(RejectedExecutionException stopping){release(session.getPlayerId(),socket);}
        });
        writers.put(session.getPlayerId(), writer);
        if (!writer.isRunning()) release(session.getPlayerId(), socket);
        return writer;
    }

    public Optional<SessionWriter> find(String playerId) {
        return Optional.ofNullable(writers.get(playerId));
    }

    public void release(String playerId) {
        SessionWriter writer = writers.remove(playerId);
        if (writer != null) {
            writer.close();
        }
    }

    public void releasePreservingSocket(String playerId) {
        SessionWriter writer = writers.remove(playerId);
        if (writer != null) {
            writer.close(false);
        }
    }

    public void release(String playerId, Socket socket) {
        SessionWriter writer = writers.get(playerId);
        if (writer != null && writer.owns(socket) && writers.remove(playerId, writer)) writer.close();
    }

    public int count() {
        return writers.size();
    }
    public Optional<Throwable> failure(){return Optional.ofNullable(failure.get());}
    public boolean isTerminated(){return watchdog.isTerminated()&&cleanup.isTerminated();}

    @Override
    public void close() {
        synchronized(this){if(closed)return;closed=true;}
        watchdog.shutdownNow();
        for (String playerId : writers.keySet()) {
            release(playerId);
        }
        cleanup.shutdown();
        try{if(!cleanup.awaitTermination(2,TimeUnit.SECONDS))cleanup.shutdownNow();watchdog.awaitTermination(1,TimeUnit.SECONDS);}
        catch(InterruptedException e){cleanup.shutdownNow();Thread.currentThread().interrupt();}
    }
}

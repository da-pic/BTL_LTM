package com.bombit.server.network;

import com.bombit.common.protocol.PacketEncoder;
import com.bombit.server.session.Session;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded writer for one captured socket, never a replacement connection. */
public final class SessionWriter implements OutboundSender, AutoCloseable {
    private record Frame(byte[] bytes, boolean last) { }
    private final Session session;
    private final Socket socket;
    private final BlockingQueue<Frame> queue;
    private final Thread worker;
    private final Runnable onFailure;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean running = true;
    private final AtomicLong pendingBytes=new AtomicLong();
    private final long byteLimit;
    private volatile long writingSince;
    private final Object lifecycle=new Object();
    private boolean accepting=true;

    public SessionWriter(Session session, int capacity) {
        this(session, capacity, () -> { });
    }
    public SessionWriter(Session session, int capacity, Runnable onFailure) {
        this(session,capacity,4L*1024*1024,onFailure);
    }
    public SessionWriter(Session session,int capacity,long byteLimit,Runnable onFailure) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid capacity");
        if(byteLimit<=0)throw new IllegalArgumentException("Invalid byte limit");
        this.byteLimit=byteLimit;
        this.session = session;
        this.socket = java.util.Objects.requireNonNull(session.getSocket());
        this.onFailure = java.util.Objects.requireNonNull(onFailure);
        queue = new ArrayBlockingQueue<>(capacity);
        worker = new Thread(this::run, "session-writer-" + session.getPlayerId());
        session.setOutboundSender(this);
        worker.start();
    }
    @Override public void send(Session target, OutboundMessage message) {
        if (target != session) throw new IllegalArgumentException("Wrong session");
        byte[] frame;
        try { frame = PacketEncoder.encode(message.toPacket()); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid outbound frame", invalid); }
        synchronized(lifecycle){
            if(!running||!accepting)throw new IllegalStateException("Outbound queue closed");
            if(pendingBytes.get()+frame.length<=byteLimit){
                pendingBytes.addAndGet(frame.length);
                if(queue.offer(new Frame(frame,false)))return;
                pendingBytes.addAndGet(-frame.length);
            }
        }
        fail();throw new IllegalStateException("Outbound queue full");
    }
    private void run() {
        try {
            var output = socket.getOutputStream();
            while (running) {
                Frame frame = queue.take();
                if (frame.last()) break;
                writingSince=System.nanoTime();
                try { output.write(frame.bytes());output.flush(); }
                finally { writingSince=0;pendingBytes.addAndGet(-frame.bytes().length); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException error) { if(running)fail(); }
        finally { synchronized(lifecycle){running=false;accepting=false;queue.clear();pendingBytes.set(0);} }
    }
    private void fail() {
        synchronized(lifecycle){running=false;accepting=false;queue.clear();}
        try { socket.close(); } catch (IOException ignored) { }
        worker.interrupt();
        if (failed.compareAndSet(false, true)) onFailure.run();
    }
    /** Bounded drain for a final ERROR/DISCONNECT response; close() still guarantees unblock. */
    public void finishAndAwait() {
        boolean full=false;
        synchronized(lifecycle){if(running&&accepting){accepting=false;full=!queue.offer(new Frame(new byte[0],true));}}
        if(full)fail();
        awaitStopped(1_000);
    }
    public boolean owns(Socket candidate) { return socket == candidate; }
    public boolean isAlive() { return worker.isAlive(); }
    public boolean isRunning() { return running; }
    public int pendingFrames(){return queue.size();}
    public long pendingBytes(){return Math.max(0,pendingBytes.get());}
    /** Called by the owning ConnectionManager watchdog; closing the captured socket unblocks write(). */
    public void expireWrite(long now,long timeoutNanos){long since=writingSince;if(running&&since!=0&&now-since>=timeoutNanos)fail();}
    private void awaitStopped(long millis) {
        if (Thread.currentThread() == worker) return;
        try { worker.join(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    public void close(boolean closeSocket) {
        synchronized(lifecycle){running=false;accepting=false;queue.clear();}
        if (closeSocket) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        worker.interrupt();
        awaitStopped(1_000);
    }
    @Override public void close() {
        close(true);
    }
}

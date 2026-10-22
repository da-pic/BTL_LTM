package com.bombit.server.network;

import com.bombit.common.protocol.*;
import com.bombit.server.session.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class SessionWriterTest {
    @Test void byteBudgetClosesConnectionEvenWhenFrameCountHasSpace()throws Exception{
        var socket=new BlockedSocket();var session=new Session("byte-budget",socket);var failures=new AtomicInteger();
        try(var writer=new SessionWriter(session,10,32,failures::incrementAndGet)){
            writer.send(session,OutboundMessage.fromPacket(new Packet(PacketType.PONG,"{}")));
            assertTrue(socket.writing.await(1,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,()->writer.send(session,OutboundMessage.fromPacket(new Packet(PacketType.PONG,"x".repeat(40)))));
            writer.close();assertFalse(writer.isAlive());assertEquals(0,writer.pendingBytes());assertEquals(1,failures.get());
        }
    }
    @Test void queueFullClosesBlockedSocketAndReportsOneFailure() throws Exception {
        var socket = new BlockedSocket();
        var session = new Session("writer", socket);
        var failures = new AtomicInteger();
        try (var writer = new SessionWriter(session, 1, failures::incrementAndGet)) {
            var packet = OutboundMessage.fromPacket(new Packet(PacketType.PONG, "{}"));
            writer.send(session, packet);
            assertTrue(socket.writing.await(1, TimeUnit.SECONDS));
            writer.send(session, packet); // Fills the only queued slot while output is blocked.
            assertThrows(IllegalStateException.class, () -> writer.send(session, packet));
            writer.close();
            assertFalse(writer.isAlive());
            assertEquals(1, failures.get());
            assertTrue(socket.isClosed());
        }
    }

    @Test void writerValidatesUtf8SizeAndSessionBeforeQueueing() throws Exception {
        var socket = new BlockedSocket();
        var session = new Session("writer", socket);
        try (var otherSocket = new Socket(); var writer = new SessionWriter(session, 1)) {
            var other = new Session("other", otherSocket);
            assertThrows(IllegalArgumentException.class, () -> writer.send(other,
                    OutboundMessage.fromPacket(new Packet(PacketType.PONG, "{}"))));
            assertThrows(IllegalArgumentException.class, () -> writer.send(session,
                    new OutboundMessage(PacketType.PONG, new byte[]{(byte) 0xc0})));
            assertThrows(IllegalArgumentException.class, () -> writer.send(session,
                    new OutboundMessage(PacketType.PONG, new byte[PacketEncoder.MAX_PACKET_LENGTH])));
            assertEquals(new Packet(PacketType.PONG, "{}"),
                    OutboundMessage.fromPacket(new Packet(PacketType.PONG, "{}")).toPacket());
        }
    }

    private static final class BlockedSocket extends Socket {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}); }
                @Override public void write(byte[] bytes) throws IOException {
                    writing.countDown();
                    try { closed.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    throw new IOException("Socket closed");
                }
            };
        }
        @Override public synchronized void close() { closed.countDown(); }
        @Override public boolean isClosed() { return closed.getCount() == 0; }
    }
}

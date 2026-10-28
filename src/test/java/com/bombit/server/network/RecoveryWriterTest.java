package com.bombit.server.network;

import com.bombit.common.protocol.*;
import com.bombit.server.session.Session;
import java.net.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class RecoveryWriterTest {
    @Test void replaceCapturedWriterAndIgnoreLateReleaseFromOldSocket() throws Exception {
        try(var listener=new ServerSocket(0);var client1=new Socket("127.0.0.1",listener.getLocalPort());
            var socket1=listener.accept();var client2=new Socket("127.0.0.1",listener.getLocalPort());
            var socket2=listener.accept();var connections=new ConnectionManager()) {
            client2.setSoTimeout(2000);
            var session=new Session("writer-swap",socket1);
            var old=connections.open(session);
            session.attachSocket(socket2);
            var fresh=connections.open(session);
            assertFalse(old.isAlive());assertTrue(socket1.isClosed());
            connections.release(session.getPlayerId(),socket1);
            assertSame(fresh,connections.find(session.getPlayerId()).orElseThrow());
            assertFalse(socket2.isClosed());
            var pong=new Packet(PacketType.PONG,"{}");
            fresh.send(session,OutboundMessage.fromPacket(pong));
            assertEquals(pong,PacketDecoder.decode(client2.getInputStream()));
            assertThrows(IllegalStateException.class,()->connections.open(session));
            assertEquals(1,connections.count());
        }
    }
}

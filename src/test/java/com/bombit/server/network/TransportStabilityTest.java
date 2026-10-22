package com.bombit.server.network;

import com.bombit.common.protocol.*;
import com.bombit.server.session.SessionState;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TransportStabilityTest {
    private static final Packet PING=new Packet(PacketType.PING,"{\"pingId\":1}");
    private static class Fixture implements AutoCloseable {
        final Server server;final Thread accept;final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture(int frame,long heartbeat,long write)throws Exception{
            server=new Server(0,frame,heartbeat,10,write);accept=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}});accept.start();
        }
        Socket open()throws Exception{var s=new Socket();s.setReceiveBufferSize(1024);s.connect(new InetSocketAddress("127.0.0.1",server.getPort()));s.setSoTimeout(2500);return s;}
        String connect(Socket s)throws Exception{send(s,SessionPacketCodec.connectRequest("stability"));return SessionPacketCodec.decodeAcknowledgement(read(s)).playerId();}
        public void close()throws Exception{server.close();accept.join(2000);assertFalse(accept.isAlive());assertNull(failure.get());
            assertEquals(0,server.getConnectionManager().count());assertTrue(server.getSessionManager().snapshot().isEmpty());assertTrue(server.isTerminated());}
    }
    @Test void heartbeatExpiresPendingAndSilentPeersButValidPingsKeepSessionAlive()throws Exception{
        try(var f=new Fixture(5000,180,1000);var pending=f.open();var active=f.open()){
            String id=f.connect(active);await(()->f.server.getConnectionManager().count()==2);
            for(int n=0;n<6;n++){send(active,PING);assertEquals(PacketType.PONG,read(active).getType());Thread.sleep(40);}
            assertEquals(SessionState.IN_LOBBY,f.server.getSessionManager().find(id).orElseThrow().getState());
            await(()->f.server.getSessionManager().snapshot().size()==1);closed(pending);
            await(()->f.server.getSessionManager().find(id).orElseThrow().getState()==SessionState.DISCONNECTED);
            await(()->f.server.getConnectionManager().count()==0);closed(active);
            assertEquals(1,f.server.getSessionManager().pendingDisconnectTimers());
            f.server.getSessionManager().hardDrop(id);assertEquals(0,f.server.getSessionManager().pendingDisconnectTimers());
            assertFalse(f.server.getRouter().hasHandler(PacketType.RECONNECT));
            try(var healthy=f.open()){f.connect(healthy);send(healthy,PING);assertEquals(PacketType.PONG,read(healthy).getType());}
            assertTrue(f.server.getHeartbeatManager().failure().isEmpty());
        }
    }
    @Test void continuousPartialFrameCannotExtendItsAbsoluteDeadline()throws Exception{
        try(var f=new Fixture(160,5000,1000);var peer=f.open()){
            peer.getOutputStream().write(new byte[]{0,0,1,0});peer.getOutputStream().flush();
            for(int n=0;n<6;n++){try{peer.getOutputStream().write(0);peer.getOutputStream().flush();}catch(IOException closed){break;}Thread.sleep(45);}
            await(()->f.server.getSessionManager().snapshot().isEmpty());assertEquals(0,f.server.getConnectionManager().count());closed(peer);
        }
    }
    @Test void blockedWriterTimesOutWhilePeerStillSendsPingsAndOtherClientsRemainUsable()throws Exception{
        try(var f=new Fixture(5000,5000,150);var slow=f.open();var healthy=f.open()){
            String id=f.connect(slow);f.connect(healthy);var session=f.server.getSessionManager().find(id).orElseThrow();
            session.getSocket().setSendBufferSize(1024);var writer=f.server.getConnectionManager().find(id).orElseThrow();
            var message=OutboundMessage.fromPacket(new Packet(PacketType.PONG,"x".repeat(900_000)));
            writer.send(session,message);writer.send(session,message);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(writer.isRunning()&&System.nanoTime()<end){try{send(slow,PING);}catch(IOException done){break;}Thread.sleep(20);}
            await(()->!writer.isAlive());assertEquals(0,writer.pendingFrames());assertEquals(0,writer.pendingBytes());
            await(()->f.server.getSessionManager().find(id).orElseThrow().getState()==SessionState.DISCONNECTED);
            assertEquals(SessionState.DISCONNECTED,f.server.getSessionManager().find(id).orElseThrow().getState());
            send(healthy,PING);assertEquals(PacketType.PONG,read(healthy).getType());
            assertTrue(f.server.getConnectionFailure().isEmpty());assertTrue(f.server.getConnectionManager().failure().isEmpty());
        }
    }
    @Test void handlerFailureClosesOnlyTheCapturedConnectionAndIsReported()throws Exception{
        try(var f=new Fixture(5000,5000,1000);var peer=f.open()){
            String id=f.connect(peer);f.server.getRouter().registerHandler(PacketType.PING,(s,p)->{throw new IllegalStateException("injected handler failure");});
            send(peer,PING);assertEquals("SERVER_ERROR",SessionPacketCodec.errorCode(read(peer)));closed(peer);
            await(()->f.server.getSessionManager().find(id).isEmpty());assertEquals("injected handler failure",f.server.getConnectionFailure().orElseThrow().getMessage());
            f.server.getRouter().registerHandler(PacketType.PING,(s,p)->new BroadcastSender().send(f.server.getSessionManager().find(s).orElseThrow(),OutboundMessage.fromPacket(new Packet(PacketType.PONG,p.getJsonPayload()))));
            try(var healthy=f.open()){f.connect(healthy);send(healthy,PING);assertEquals(PacketType.PONG,read(healthy).getType());}
        }
    }
    @Test void chatLimitAndTotalFloodAreEnforcedBeforeHandlers()throws Exception{
        try(var f=new Fixture(5000,5000,1000);var peer=f.open()){
            f.connect(peer);var routed=new AtomicInteger();f.server.getRouter().registerHandler(PacketType.CHAT,(id,p)->routed.incrementAndGet());
            for(int n=0;n<6;n++)send(peer,new Packet(PacketType.CHAT,"{}"));
            assertEquals("RATE_LIMITED",SessionPacketCodec.errorCode(read(peer)));assertEquals(5,routed.get());
        }
        try(var f=new Fixture(5000,5000,1000);var peer=f.open()){
            String id=f.connect(peer);var bytes=new ByteArrayOutputStream();
            for(int n=0;n<120;n++)PacketEncoder.encode(PING,bytes);peer.getOutputStream().write(bytes.toByteArray());peer.getOutputStream().flush();
            Packet p;do{p=read(peer);}while(p.getType()==PacketType.PONG);
            assertEquals("RATE_LIMITED",SessionPacketCodec.errorCode(p));closed(peer);await(()->f.server.getSessionManager().find(id).isEmpty());
        }
    }
    @Test void fixedWindowIncludesInvalidTrafficAndResetsAtExactBoundary(){
        var limit=new InboundRateLimiter();
        for(int n=0;n<100;n++)assertEquals(InboundRateLimiter.Decision.ALLOW,limit.check((short)0x7fff,0));
        assertEquals(InboundRateLimiter.Decision.CLOSE,limit.check(PacketType.PING,999_999_999));
        assertEquals(InboundRateLimiter.Decision.ALLOW,limit.check(PacketType.PING,1_000_000_000));
        for(int n=0;n<5;n++)assertEquals(InboundRateLimiter.Decision.ALLOW,limit.check(PacketType.LIST_ROOMS,1_000_000_000));
        assertEquals(InboundRateLimiter.Decision.REJECT,limit.check(PacketType.LIST_ROOMS,1_000_000_000));
    }
    private static void send(Socket s,Packet p)throws IOException{PacketEncoder.encode(p,s.getOutputStream());}
    private static Packet read(Socket s)throws IOException{return PacketDecoder.decode(s.getInputStream());}
    private static void closed(Socket s)throws IOException{try{assertEquals(-1,s.getInputStream().read());}catch(SocketException reset){}}
    private static void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(done.getAsBoolean(),"Cleanup deadline");}
}

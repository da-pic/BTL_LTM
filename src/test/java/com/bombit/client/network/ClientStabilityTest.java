package com.bombit.client.network;

import com.bombit.client.app.DemoClientConnection;
import com.bombit.common.protocol.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ClientStabilityTest {
    @Test void standaloneMovementControllerAlsoBoundsItsUiQueueAndReportsOnlyOneFailure()throws Exception{
        var queued=new ArrayBlockingQueue<Runnable>(300);var errors=new AtomicInteger();
        try(var network=new ClientNetwork();var controller=new com.bombit.client.game.movement.MovementClientController(network,1,queued::add,()->{},e->errors.incrementAndGet())){
            for(int n=1;n<=300;n++){
                var full=com.bombit.server.game.movement.GameStateUpdate.fullStateModel(n,1,1,32,com.bombit.server.game.core.GameState.MatchState.RUNNING,
                        java.util.List.of(com.bombit.server.game.model.TileType.EMPTY),java.util.List.of());
                controller.acceptPacket(com.bombit.server.game.movement.SnapshotPacketCodec.encode(full));
            }
            assertTrue(controller.isClosed());assertTrue(queued.size()<=257);
            Runnable task;while((task=queued.poll())!=null)task.run();assertEquals(1,errors.get());assertTrue(controller.isSenderStopped());assertEquals(0,network.listenerCount());
        }
    }
    @Test void pullByteBudgetClosesBeforeCountLimitOnLargeFrames()throws Exception{
        try(var listener=new ServerSocket(0);var client=new ClientNetwork()){
            var error=new AtomicReference<Throwable>();client.setOnDisconnect(error::set);client.connect("127.0.0.1",listener.getLocalPort());
            try(var peer=listener.accept()){
                var packet=new Packet(PacketType.PONG,"x".repeat(900_000));
                try{for(int n=0;n<5;n++)PacketEncoder.encode(packet,peer.getOutputStream());}catch(IOException closed){}
                await(()->error.get()!=null&&!client.isReaderAlive());assertEquals(4,client.getQueuedPacketCount());assertTrue(client.queuedBytes()<=4L*1024*1024);
            }
        }
    }
    @Test void displayByteBudgetClosesBeforeUiSlotLimitOnLargeFrames()throws Exception{
        var queue=new ArrayBlockingQueue<Runnable>(300);var errors=new AtomicInteger();
        try(var listener=new ServerSocket(0);var app=new DemoClientConnection(queue::add,c->{},s->{},e->errors.incrementAndGet())){
            app.connect("127.0.0.1",listener.getLocalPort(),"byte budget");
            try(var peer=listener.accept()){
                PacketDecoder.decode(peer.getInputStream());var packet=new Packet(PacketType.PONG,"x".repeat(900_000));
                try{for(int n=0;n<5;n++)PacketEncoder.encode(packet,peer.getOutputStream());}catch(IOException closed){}
                await(()->app.isClosed()&&app.workersStopped());assertTrue(queue.size()<=5);
                Runnable task;while((task=queue.poll())!=null)task.run();assertEquals(1,errors.get());
            }
        }
    }
    @Test void fullPullQueueDisconnectsInsteadOfSilentlyDroppingSnapshotData()throws Exception{
        try(var listener=new ServerSocket(0);var client=new ClientNetwork()){
            var error=new AtomicReference<Throwable>();client.setOnDisconnect(error::set);client.connect("127.0.0.1",listener.getLocalPort());
            try(var peer=listener.accept()){
                for(int n=0;n<1025;n++)PacketEncoder.encode(new Packet(PacketType.PONG,"{}"),peer.getOutputStream());
                await(()->error.get()!=null&&!client.isReaderAlive());assertTrue(error.get().getMessage().contains("queue full"));
                assertEquals(1024,client.getQueuedPacketCount());
            }
            client.close();assertEquals(0,client.getQueuedPacketCount());assertEquals(0,client.listenerCount());
        }
    }
    @Test void listenerFailureIsReportedAndCloseRemovesAllRegistrations()throws Exception{
        try(var listener=new ServerSocket(0);var client=new ClientNetwork()){
            var error=new AtomicReference<Throwable>();var callbacks=new AtomicInteger();client.setOnDisconnect(e->{callbacks.incrementAndGet();error.set(e);});
            client.addPacketListener(p->{throw new IllegalStateException("injected listener");});client.addPacketListener(PacketType.PONG,p->{});
            client.connect("127.0.0.1",listener.getLocalPort());try(var peer=listener.accept()){
                PacketEncoder.encode(new Packet(PacketType.PONG,"{}"),peer.getOutputStream());await(()->!client.isReaderAlive());
                assertEquals(1,callbacks.get());assertEquals("injected listener",error.get().getCause().getMessage());
            }
            client.close();assertEquals(0,client.listenerCount());
        }
    }
    @Test void clientWriteDeadlineUnblocksSenderWhenServerNeverReads()throws Exception{
        try(var listener=new ServerSocket(0);var client=new ClientNetwork(150)){
            var error=new AtomicReference<Throwable>();client.setOnDisconnect(error::set);client.connect("127.0.0.1",listener.getLocalPort());
            try(var peer=listener.accept()){
                peer.setReceiveBufferSize(1024);var pool=Executors.newSingleThreadExecutor();
                try{
                    var send=pool.submit(()->{try{for(int n=0;n<30;n++)client.send(new Packet(PacketType.PING,"x".repeat(900_000)));}catch(IOException e){return;}throw new AssertionError("Expected blocked write to fail");});
                    send.get(3,TimeUnit.SECONDS);await(()->error.get()!=null&&!client.isReaderAlive());
                }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(1,TimeUnit.SECONDS));}
            }
        }
    }
    @Test void stalledUiHasBoundedDispatchAndFailsOnceThenReleasesNetworkAndTimer()throws Exception{
        var queued=new ArrayBlockingQueue<Runnable>(300);var errors=new AtomicInteger();
        try(var listener=new ServerSocket(0);var app=new DemoClientConnection(queued::add,c->{},s->{},e->errors.incrementAndGet())){
            app.connect("127.0.0.1",listener.getLocalPort(),"stalled UI");
            try(var peer=listener.accept()){
                PacketDecoder.decode(peer.getInputStream());
                for(int n=0;n<257;n++)PacketEncoder.encode(new Packet(PacketType.PONG,"{}"),peer.getOutputStream());
                await(()->app.isClosed()&&app.workersStopped());assertTrue(queued.size()<=257);
                Runnable task;while((task=queued.poll())!=null)task.run();assertEquals(1,errors.get());
            }
        }
    }
    private static void await(BooleanSupplier done)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(done.getAsBoolean(),"Client cleanup deadline");}
}

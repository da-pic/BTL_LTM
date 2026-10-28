package com.bombit.client.network;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ConnectCancellationTest {
    @Test void closeCancelsPendingConnectWithoutWaitingForConnectMonitor() throws Exception {
        var entered=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        Socket socket=new Socket(){
            @Override public void connect(SocketAddress endpoint,int timeout) throws IOException {
                entered.countDown();
                try{cancelled.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                throw new SocketException("cancelled");
            }
            @Override public synchronized void close() throws IOException {super.close();cancelled.countDown();}
        };
        try(var network=new ClientNetwork(5000,()->socket)) {
            var pending=pool.submit(()->assertThrows(IOException.class,()->network.connect("127.0.0.1",8888,1500)));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            pool.submit(network::close).get(1,TimeUnit.SECONDS);
            pending.get(1,TimeUnit.SECONDS);
            assertTrue(socket.isClosed());assertFalse(network.isConnected());assertFalse(network.isReaderAlive());
        } finally {cancelled.countDown();pool.shutdownNow();}
    }
}

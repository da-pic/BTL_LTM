package com.bombit.server.session;

import com.bombit.server.integration.GameEventSink;
import com.bombit.server.heartbeat.HeartbeatManager;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class SessionFaultTest {
    @Test void throwingLifecycleCallbacksCannotCancelSweepsOrLeakGraceTimers()throws Exception{
        var dropped=new AtomicInteger();
        var observer=new GameEventSink(){
            public void onPlayerDisconnected(Session s){throw new IllegalStateException("injected disconnect callback");}
            public void onPlayerHardDropped(Session s){dropped.incrementAndGet();throw new IllegalStateException("injected drop callback");}
            public void onPlayerReconnected(Session s){}
        };
        var sessions=new SessionManager(observer,1);var heartbeat=new HeartbeatManager(sessions,50,TimeUnit.MILLISECONDS,(s,m)->{},10);
        try(sessions;heartbeat){
            for(int n=0;n<3;n++){var session=new Session("fault "+n,new Socket());session.completeHandshake("name");sessions.register(session);}
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!sessions.snapshot().isEmpty()&&System.nanoTime()<end)Thread.sleep(10);
            assertTrue(sessions.snapshot().isEmpty());assertEquals(0,sessions.pendingDisconnectTimers());assertEquals(3,dropped.get());
            assertEquals("injected disconnect callback",sessions.failure().orElseThrow().getMessage());
            assertTrue(heartbeat.failure().isEmpty());
        }
        assertTrue(sessions.isTerminated());assertTrue(heartbeat.isTerminated());
    }
}

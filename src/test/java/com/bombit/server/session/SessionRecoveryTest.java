package com.bombit.server.session;

import com.bombit.server.integration.GameEventSink;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Chạy các timer đã bị cancel một cách chủ động để tái hiện callback đến muộn. */
@Timeout(8)
class SessionRecoveryTest {
    @ParameterizedTest
    @CsvSource({"14999999999,true","15000000000,false","15000000001,false"})
    void reconnectAdmissionIsPreciseBeforeAtAndAfterFifteenSeconds(long elapsedNanos,boolean admitted) throws Exception {
        var clock=new AtomicLong();var timers=new Timers();var events=new Events();
        try(var manager=new SessionManager(events,15,clock::get,timers);var candidate=new Socket()) {
            var session=playing(manager,new Socket());manager.handleUnexpectedDisconnect(session.getPlayerId());
            clock.set(elapsedNanos);
            assertEquals(admitted,manager.canReconnect(session.getPlayerId(),session.getReconnectToken()));
            assertEquals(admitted,manager.reconnect(session.getPlayerId(),session.getReconnectToken(),candidate));
            if(admitted) {assertSame(candidate,session.getSocket());assertEquals(1,events.resumes);}
            else {assertTrue(candidate.isClosed());timers.tasks.getFirst().run();assertTrue(manager.snapshot().isEmpty());}
            assertEquals(0,manager.pendingDisconnectTimers());
            System.out.println("RECOVERY_BOUNDARY elapsed_nanos="+elapsedNanos+" admitted="+admitted);
        }
    }
    private static final class Timers extends ScheduledThreadPoolExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        Timers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            tasks.add(task);
            return super.schedule(task, 1, TimeUnit.DAYS);
        }
    }
    private static final class Events implements GameEventSink {
        int drops, resumes;
        public void onPlayerDisconnected(Session s) { }
        public void onPlayerReconnected(Session s) { resumes++; }
        public void onPlayerHardDropped(Session s) { drops++; }
    }
    private static Session playing(SessionManager manager, Socket socket) {
        Session s = new Session("recovery", socket); s.setState(SessionState.PLAYING); manager.register(s); return s;
    }
    @Test void oldTimerCannotExpireResumedSessionOrItsNextGraceWindow() throws Exception {
        var clock = new AtomicLong(); var timers = new Timers(); var events = new Events();
        try(var manager = new SessionManager(events,15,clock::get,timers)) {
            var s=playing(manager,new Socket()); String id=s.getPlayerId();
            manager.handleUnexpectedDisconnect(id);
            Runnable old=timers.tasks.getFirst();
            clock.set(TimeUnit.SECONDS.toNanos(14));
            assertTrue(manager.reconnect(id,s.getReconnectToken(),new Socket()));
            old.run(); assertEquals(SessionState.PLAYING,s.getState());
            manager.handleUnexpectedDisconnect(id);
            clock.set(TimeUnit.SECONDS.toNanos(16));
            old.run(); // Đã quá deadline cũ nhưng grace của lần mất mạng thứ hai chưa hết.
            assertEquals(SessionState.DISCONNECTED,s.getState()); assertEquals(0,events.drops);
            clock.set(TimeUnit.SECONDS.toNanos(29)); timers.tasks.get(1).run();
            assertTrue(manager.find(id).isEmpty()); assertEquals(1,events.drops);
            assertEquals(0,manager.pendingDisconnectTimers());
        }
    }
    @Test void deadlineIsEnforcedEvenWhenExpiryExecutorHasNotRun() throws Exception {
        var clock=new AtomicLong(); var timers=new Timers();
        try(var manager=new SessionManager(new Events(),15,clock::get,timers);var candidate=new Socket()) {
            var s=playing(manager,new Socket()); manager.handleUnexpectedDisconnect(s.getPlayerId());
            clock.set(TimeUnit.SECONDS.toNanos(15));
            assertFalse(manager.canReconnect(s.getPlayerId(),s.getReconnectToken()));
            assertFalse(manager.reconnect(s.getPlayerId(),s.getReconnectToken(),candidate));
            assertTrue(candidate.isClosed()); timers.tasks.getFirst().run();
            assertTrue(manager.snapshot().isEmpty());
        }
    }
    @Test void oldSocketCallbacksCannotDropReplacement() throws Exception {
        var events=new Events();
        try(var manager=new SessionManager(events,15)) {
            Socket old=new Socket(), fresh=new Socket(); var s=playing(manager,old);
            assertTrue(manager.reconnect(s.getPlayerId(),s.getReconnectToken(),fresh)); // half-open takeover
            manager.handleUnexpectedDisconnect(s.getPlayerId(),old);
            manager.hardDrop(s.getPlayerId(),old);
            assertSame(fresh,s.getSocket()); assertFalse(fresh.isClosed());
            assertEquals(SessionState.PLAYING,s.getState()); assertEquals(1,events.resumes);
            assertEquals(0,events.drops); assertEquals(0,manager.pendingDisconnectTimers());
        }
    }
    @Test void overlappingReconnectCannotReplaceWinnerDuringWriterSetup() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try(var manager=new SessionManager(new Events(),15);var loser=new Socket()) {
            var s=playing(manager,new Socket());manager.handleUnexpectedDisconnect(s.getPlayerId());
            var winner=new Socket();
            var task=pool.submit(()->manager.reconnect(s.getPlayerId(),s.getReconnectToken(),winner,resumed->{
                entered.countDown();
                try { if(!release.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("Missing barrier"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertFalse(manager.reconnect(s.getPlayerId(),s.getReconnectToken(),loser));
                assertTrue(loser.isClosed());assertSame(winner,s.getSocket());
            } finally {release.countDown();}
            assertTrue(task.get(2,TimeUnit.SECONDS));assertSame(winner,s.getSocket());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void writerSetupFailureClosesSessionAndCancelsGrace() throws Exception {
        var events=new Events();
        try(var manager=new SessionManager(events,15);var candidate=new Socket()) {
            var s=playing(manager,new Socket());manager.handleUnexpectedDisconnect(s.getPlayerId());
            assertFalse(manager.reconnect(s.getPlayerId(),s.getReconnectToken(),candidate,
                    resumed->{throw new IllegalStateException("injected writer failure");}));
            assertTrue(candidate.isClosed());assertTrue(manager.snapshot().isEmpty());
            assertEquals(0,manager.pendingDisconnectTimers());assertEquals(1,events.drops);
            assertEquals(0,events.resumes);
            assertEquals("injected writer failure",manager.failure().orElseThrow().getMessage());
        }
    }
    @Test void shutdownDuringWriterSetupDoesNotResurrectSession() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var events=new Events();
        try(var manager=new SessionManager(events,15);var candidate=new Socket()) {
            var s=playing(manager,new Socket());manager.handleUnexpectedDisconnect(s.getPlayerId());
            var task=pool.submit(()->manager.reconnect(s.getPlayerId(),s.getReconnectToken(),candidate,resumed->{
                entered.countDown();
                try {if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("Missing barrier");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }));
            try {assertTrue(entered.await(2,TimeUnit.SECONDS));manager.close();}
            finally {release.countDown();}
            assertFalse(task.get(2,TimeUnit.SECONDS));assertTrue(candidate.isClosed());
            assertEquals(1,events.drops);assertEquals(0,events.resumes);
            assertTrue(manager.snapshot().isEmpty());assertEquals(0,manager.pendingDisconnectTimers());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void staleHeartbeatDecisionCannotDisconnectFreshlyUpdatedSession() throws Exception {
        try(var manager=new SessionManager(new Events(),15);var socket=new Socket()) {
            var s=playing(manager,socket);s.updateHeartbeat();
            manager.handleHeartbeatTimeout(s.getPlayerId(),socket,TimeUnit.DAYS.toNanos(1));
            assertSame(socket,s.getSocket());assertEquals(SessionState.PLAYING,s.getState());
            assertEquals(0,manager.pendingDisconnectTimers());
        }
    }
}

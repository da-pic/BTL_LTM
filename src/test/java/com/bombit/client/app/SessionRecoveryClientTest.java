package com.bombit.client.app;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** TCP thật với server có thể giữ ACK/full hoặc ngắt đúng giữa các pha recovery. */
@Timeout(12)
class SessionRecoveryClientTest {
    private static final GameClientConnection.RecoveryTimeouts FAST=
            new GameClientConnection.RecoveryTimeouts(700,120,140,20,150);

    @Test void ackAndDeltaDoNotUnlockInputOnlyAppliedFullDoesAndCountersSurvive() throws Exception {
        try(var f=new Fixture(new GameClientConnection.RecoveryTimeouts(4000,1000,1500,30,200))) {
            Socket first=f.start();MovementClientController original=f.call(f.app::movement);
            var prediction=f.call(original::prediction);
            f.call(()->{original.send(prediction.predict(Direction.RIGHT,true));original.plantBomb();return null;});
            assertEquals(PacketType.MOVE,read(first).getType());
            assertEquals(1,BombPacketCodec.decodePlant(MatchEnvelopeCodec.unwrap(read(first),f.match.matchId())));
            f.sendFull(first,2,1);f.await(()->original.world().getTick()==2);
            f.call(()->{prediction.predict(Direction.RIGHT,true);return null;}); // Input chưa gửi/ACK.
            first.close();Socket resumed=f.reconnect();
            f.await(()->f.app.recoveryState()==GameClientConnection.RecoveryState.WAITING_ACK);
            f.assertLocked(resumed);
            f.ack(resumed);f.await(()->f.app.recoveryState()==GameClientConnection.RecoveryState.WAITING_FULL);
            f.metadata(resumed);f.assertLocked(resumed);
            var delta=GameStateUpdate.delta(3,2,3,3,32,GameState.MatchState.RUNNING,false,List.of(),List.of(),List.of());
            send(resumed,MatchEnvelopeCodec.wrap(f.match.matchId(),SnapshotPacketCodec.encode(delta)));
            f.sendFull(resumed,2,1); // full trùng tick không được coi là phục hồi thành công.
            f.call(()->null);assertTrue(f.app.isReconnecting());
            assertFalse(f.call(original::isInputReady));
            f.sendFull(resumed,3,1);f.await(()->!f.app.isReconnecting()&&original.isInputReady());
            assertSame(original,f.app.movement());assertSame(prediction,f.call(original::prediction));
            assertEquals(0,f.call(prediction::pendingCount));assertEquals(3L,f.call(()->original.world().getTick()));
            f.call(()->{original.send(prediction.predict(Direction.DOWN,true));original.plantBomb();return null;});
            var move=MovePacketCodec.decode(MatchEnvelopeCodec.unwrap(read(resumed),f.match.matchId()));
            assertTrue(move.sequence()>2);
            assertEquals(2,BombPacketCodec.decodePlant(MatchEnvelopeCodec.unwrap(read(resumed),f.match.matchId())));
            assertNull(f.failure.get());assertFalse(f.wrongThread.get());
        }
    }
    @Test void missingAckRetriesAndReachesTotalDeadlineInsteadOfHanging() throws Exception {
        silentPhase(false);
    }
    @Test void endedMatchRecoveryDeliversResultWithoutFullOrRecreatingController() throws Exception {
        try(var f=new Fixture(new GameClientConnection.RecoveryTimeouts(4000,1000,1500,30,200))) {
            var results=new AtomicInteger();
            f.app.setLobbyListener(p->{if(p.getType()==PacketType.GAME_OVER)results.incrementAndGet();});
            Socket first=f.start();var original=f.call(f.app::movement);
            first.close();Socket resumed=f.reconnect();f.ack(resumed);
            f.await(()->f.app.recoveryState()==GameClientConnection.RecoveryState.WAITING_FULL);
            var result=new MatchResult(9,1,MatchResult.Reason.LAST_ALIVE,List.of(new MatchResult.Ranking(1,1)));
            send(resumed,MatchEnvelopeCodec.wrap(f.match.matchId(),GameplayPacketCodec.result(result)));
            f.await(()->results.get()==1&&!f.app.isReconnecting());
            assertEquals(result,f.call(f.app::lastResult));
            assertEquals(1,f.call(f.app::localId)); // Còn identity để UI hiện kết quả local.
            assertNull(f.call(f.app::context));assertNull(f.call(f.app::movement));
            assertTrue(original.isClosed());assertFalse(original.isInputReady());
            assertFalse(f.app.isClosed());assertNull(f.failure.get());
            assertEquals(GameClientConnection.RecoveryState.CONNECTED,f.app.recoveryState());
        }
    }
    @Test void missingFullRetriesAndReachesTotalDeadlineDespiteOtherPackets() throws Exception {
        silentPhase(true);
    }
    private void silentPhase(boolean acknowledge) throws Exception {
        try(var f=new Fixture(FAST)) {
            f.start().close();int attempts=0;
            while(!f.app.isClosed()) {
                Socket peer;
                try{peer=f.reconnect();}catch(SocketTimeoutException timeout){if(f.app.isClosed())break;throw timeout;}
                attempts++;
                if(acknowledge){f.ack(peer);f.metadata(peer);send(peer,new Packet(PacketType.PONG,"{}"));}
            }
            f.await(()->f.failures.get()==1&&f.app.workersStopped());
            assertTrue(attempts>=2);assertTrue(f.failure.get().getMessage().contains("deadline"));
            assertFalse(f.app.isReconnecting());assertEquals(GameClientConnection.RecoveryState.CLOSED,f.app.recoveryState());
            assertFalse(f.wrongThread.get());
        }
    }
    @Test void retriesWhenConnectionDropsBeforeAckAndAgainBeforeFull() throws Exception {
        try(var f=new Fixture(new GameClientConnection.RecoveryTimeouts(4000,700,900,20,200))) {
            Socket first=f.start();var controller=f.app.movement();first.close();
            Socket noAck=f.reconnect();noAck.close();
            Socket noFull=f.reconnect();f.ack(noFull);
            f.await(()->f.app.recoveryState()==GameClientConnection.RecoveryState.WAITING_FULL);
            f.metadata(noFull);noFull.close();
            Socket winner=f.reconnect();f.ack(winner);f.metadata(winner);f.sendFull(winner,5,0);
            f.await(()->!f.app.isReconnecting()&&controller.isInputReady());
            assertSame(controller,f.app.movement());assertNull(f.failure.get());
        }
    }
    @Test void rejectionIsFatalEvenWithLobbyListenerInstalled() throws Exception {
        try(var f=new Fixture(FAST)) {
            f.start().close();Socket peer=f.reconnect();
            send(peer,SessionPacketCodec.error("RECONNECT_REJECTED"));
            f.await(()->f.failures.get()==1&&f.app.workersStopped());
            assertTrue(f.failure.get().getMessage().contains("RECONNECT_REJECTED"));
        }
    }
    @Test void wrongIdentityAckCannotResumeAnotherPlayer() throws Exception {
        try(var f=new Fixture(FAST)) {
            f.start().close();Socket peer=f.reconnect();
            send(peer,SessionPacketCodec.acknowledgement(UUID.randomUUID().toString(),f.token));
            f.await(()->f.failures.get()==1&&f.app.workersStopped());
            assertTrue(f.failure.get().getMessage().contains("identity mismatch"));
        }
    }
    @Test void closeDuringRecoveryCancelsTimersAndReportsNoSpuriousFailure() throws Exception {
        try(var f=new Fixture(FAST)) {
            f.start().close();f.reconnect();f.app.close();
            f.await(()->f.app.workersStopped());
            assertEquals(0,f.failures.get());assertEquals(0,f.app.listenerCount());
            assertEquals(GameClientConnection.RecoveryState.CLOSED,f.app.recoveryState());
        }
    }
    @Test void callbackStatesAlsoRunOnSwingEdt() throws Exception {
        // Dùng EDT thật, không mở JFrame/cửa sổ tương tác trong test.
        try(var f=new Fixture(new GameClientConnection.RecoveryTimeouts(2500,600,900,30,200),true)) {
            f.start().close();Socket peer=f.reconnect();f.ack(peer);
            f.await(()->f.states.contains(GameClientConnection.RecoveryState.WAITING_FULL));
            f.metadata(peer);f.sendFull(peer,2,0);
            f.await(()->!f.app.isReconnecting()&&f.app.movement().isInputReady());
            assertFalse(f.wrongThread.get());assertNull(f.failure.get());
            assertTrue(f.states.contains(GameClientConnection.RecoveryState.WAITING_FULL));
        }
    }
    @Test void packetsQueuedByPreviousAttemptCannotOverwriteNewConnectionWorld() throws Exception {
        var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new Fixture(new GameClientConnection.RecoveryTimeouts(5000,2000,2000,30,200))) {
            Socket first=f.start();var controller=f.app.movement();
            f.ui.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try {
                assertTrue(blocked.await(1,TimeUnit.SECONDS));first.close();
                Socket old=f.reconnect();f.ack(old);f.metadata(old);f.sendFull(old,50,0);
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(f.app.pendingUiActions()<7&&System.nanoTime()<end)Thread.sleep(5);
                assertTrue(f.app.pendingUiActions()>=7,"Old packet actions must be queued before dropping this attempt");
                old.close();Socket winner=f.reconnect();release.countDown();
                f.call(()->null);assertEquals(1L,f.call(()->controller.world().getTick()));
                assertTrue(f.app.isReconnecting());assertFalse(f.call(controller::isInputReady));
                f.ack(winner);f.metadata(winner);f.sendFull(winner,2,0);
                f.await(()->!f.app.isReconnecting()&&controller.isInputReady());
                assertEquals(2L,f.call(()->controller.world().getTick()));assertNull(f.failure.get());
            } finally {release.countDown();}
        }
    }
    private static Packet read(Socket socket) throws IOException {return PacketDecoder.decode(socket.getInputStream());}
    private static void send(Socket socket,Packet packet) throws IOException {PacketEncoder.encode(packet,socket.getOutputStream());}

    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0);
        final ExecutorService ui=Executors.newSingleThreadExecutor();
        final AtomicReference<Thread> uiThread=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger failures=new AtomicInteger();
        final AtomicBoolean wrongThread=new AtomicBoolean();
        final List<GameClientConnection.RecoveryState> states=new CopyOnWriteArrayList<>();
        final List<Socket> sockets=new ArrayList<>();
        final String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        // Theme khác mặc định phải được giữ trong metadata của cùng trận khi reconnect.
        final MatchContext match=new MatchContext(UUID.randomUUID().toString(),UUID.randomUUID(),MapTheme.ICE,List.of(new MatchContext.PlayerIdentity(id,1)));
        final GameClientConnection app;
        final boolean swing;
        Fixture(GameClientConnection.RecoveryTimeouts timeouts) throws Exception {this(timeouts,false);}
        Fixture(GameClientConnection.RecoveryTimeouts timeouts,boolean swing) throws Exception {
            this.swing=swing;listener.setSoTimeout(1500);ui.submit(()->uiThread.set(Thread.currentThread())).get();
            java.util.concurrent.Executor executor=swing?javax.swing.SwingUtilities::invokeLater:ui;
            app=new GameClientConnection(executor,c->{},s->checkThread(),e->{checkThread();failures.incrementAndGet();failure.set(e);},timeouts);
            app.setAutoReconnect(true);app.setLobbyListener(p->{});
            app.setRecoveryStateListener(state->{checkThread();states.add(state);});
        }
        void checkThread(){if(swing?!javax.swing.SwingUtilities.isEventDispatchThread():Thread.currentThread()!=uiThread.get())wrongThread.set(true);}
        Socket accept() throws IOException {Socket s=listener.accept();s.setSoTimeout(1500);sockets.add(s);return s;}
        Socket start() throws Exception {
            app.connect("127.0.0.1",listener.getLocalPort(),"test");Socket peer=accept();assertEquals(PacketType.CONNECT,read(peer).getType());
            ack(peer);metadata(peer);sendFull(peer,1,0);await(()->app.movement()!=null&&app.movement().isInputReady());return peer;
        }
        Socket reconnect() throws Exception {
            Socket peer=accept();var request=SessionPacketCodec.reconnect(read(peer));
            assertEquals(id,request.playerId());assertEquals(token,request.reconnectToken());return peer;
        }
        void ack(Socket peer) throws IOException {send(peer,SessionPacketCodec.acknowledgement(id,token));}
        void metadata(Socket peer) throws IOException {send(peer,DemoPacketCodec.started(match,Map.of(id,"test")));}
        void sendFull(Socket peer,long tick,long ack) throws IOException {
            var player=new GameStateUpdate.PlayerData(1,4,4,24,24,80,Direction.DOWN,false,true,ack);
            var update=GameStateUpdate.fullStateModel(tick,3,3,32,GameState.MatchState.RUNNING,
                    Collections.nCopies(9,TileType.EMPTY),List.of(player)).withBombFrame(new BombFrame(System.currentTimeMillis(),List.of()));
            send(peer,MatchEnvelopeCodec.wrap(match.matchId(),SnapshotPacketCodec.encodeFullSync(update)));
        }
        <T>T call(Callable<T> action) throws Exception {
            if(!swing)return ui.submit(action).get(2,TimeUnit.SECONDS);
            var task=new FutureTask<T>(action);javax.swing.SwingUtilities.invokeAndWait(task);return task.get();
        }
        void await(BooleanSupplier done) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(!call(done::getAsBoolean)&&System.nanoTime()<end)Thread.sleep(5);
            assertTrue(call(done::getAsBoolean),"Client state deadline");
        }
        void assertLocked(Socket peer) throws Exception {
            assertTrue(app.isReconnecting());assertFalse(call(()->app.movement().isInputReady()));
            call(()->{app.movement().send(new MovePacketCodec.Input(99,Direction.RIGHT,true));app.movement().plantBomb();
                app.send(LobbyPacketCodec.packet(PacketType.CHAT,Map.of("message","offline")));return null;});
            peer.setSoTimeout(80);
            try{assertThrows(SocketTimeoutException.class,()->read(peer));}finally{peer.setSoTimeout(1500);}
        }
        public void close() throws Exception {
            app.close();for(Socket s:sockets)s.close();listener.close();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!app.workersStopped()&&System.nanoTime()<end)Thread.sleep(5);
            assertTrue(app.workersStopped());
            ui.shutdown();assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
}

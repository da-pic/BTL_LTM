package com.bombit.integration;

import com.bombit.client.app.DemoClientConnection;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import com.bombit.server.app.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.GameRuntimeManager;
import com.bombit.server.game.runtime.GameplayExtension;
import com.bombit.server.session.SessionState;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class LobbyLifecycleTest {
    @Test void fourPlayerRoomRejectsFifthAndCanonicalRequestsWork() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B");var c=new Peer(f,"C");var d=new Peer(f,"D");var fifth=new Peer(f,"E")) {
            a.command(PacketType.CREATE_ROOM,Map.of("roomName","Four players","maxPlayers",4,"mode","STANDARD"));
            var room=LobbyPacketCodec.decodeRoom(a.take(PacketType.ROOM_STATE));String id=room.roomId();
            for(Peer p:List.of(b,c,d)) join(p,id);
            fifth.command(PacketType.JOIN_ROOM,Map.of("roomId",id));fifth.error("ROOM_FULL");
            for(Peer p:List.of(b,c,d)) {
                p.command(PacketType.READY,Map.of("roomId",id,"ready",true));
                p.until(v->v.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(v).players().stream().anyMatch(x->x.playerId().equals(p.id)&&x.state().equals("READY")));
            }
            a.command(PacketType.START_GAME,Map.of("roomId",id));
            var match=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));assertEquals(4,match.players().size());
            for(Peer p:List.of(b,c,d)) p.take(PacketType.GAME_STARTED);
            for(Peer p:List.of(a,b,c,d)) assertFull(p,match);
            d.command(PacketType.LEAVE_ROOM,Map.of("roomId",id));
            var ack=LobbyJson.object(d.take(PacketType.LEAVE_ROOM).getJsonPayload());assertEquals(id,ack.get("roomId"));assertEquals(true,ack.get("left"));
            for(Peer p:List.of(a,b,c)) p.take(PacketType.GAME_OVER);
            waitFor(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
        }
    }
    @Test void independentRoomsChatMovementResyncAndCleanup() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B");var c=new Peer(f,"C");var d=new Peer(f,"D")) {
            String first=create(a,2);join(b,first);String second=create(c,2);join(d,second);
            ready(b);ready(d);a.command(PacketType.START_GAME);c.command(PacketType.START_GAME);
            var x=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));b.take(PacketType.GAME_STARTED);
            var y=DemoPacketCodec.decodeStarted(c.take(PacketType.GAME_STARTED));d.take(PacketType.GAME_STARTED);
            assertNotEquals(x.matchId(),y.matchId());assertEquals(2,f.server.activeMatchCount());
            assertFull(a,x);assertFull(b,x);assertFull(c,y);assertFull(d,y);
            a.send(MatchEnvelopeCodec.wrap(x.matchId(),MovePacketCodec.encode(new MovePacketCodec.Input(1,Direction.RIGHT,true))));
            int player=x.playerId(a.id).orElseThrow();
            b.until(p -> p.getType()==PacketType.GAME_SNAPSHOT && snapshot(p,x).getPlayers().stream().anyMatch(v->v.getId()==player&&v.getLastProcessedInput()==1));
            a.command(PacketType.CHAT,Map.of("message","hello \"room one\""));
            assertEquals(first,LobbyPacketCodec.decodeChat(b.take(PacketType.CHAT)).roomId());
            c.drain(200);d.drain(200);
            assertTrue(c.history.stream().noneMatch(p->p.getType()==PacketType.CHAT));
            for(Packet p:c.history) if(p.getType()==PacketType.GAME_SNAPSHOT) MatchEnvelopeCodec.unwrap(p,y.matchId());
            a.send(MatchEnvelopeCodec.wrap(x.matchId(),ResyncPacketCodec.encode(new ResyncPacketCodec.Request(-1,ResyncPacketCodec.Reason.BASELINE_MISMATCH))));
            MatchEnvelopeCodec.unwrap(b.take(PacketType.FULL_STATE_SYNC),x.matchId());
            assertEquals(2,f.server.activeMatchCount());
        }
    }
    @Test void permissionsCapacityValidationAndReadyAreAuthoritative() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B");var c=new Peer(f,"C")) {
            a.command(PacketType.CREATE_ROOM,Map.of("roomName","invalid","maxPlayers",5));a.error("INVALID_PACKET");
            String id=create(a,2);join(b,id);
            c.command(PacketType.JOIN_ROOM,Map.of("roomId",id));c.error("ROOM_FULL");
            b.command(PacketType.READY,Map.of("roomId",UUID.randomUUID().toString(),"ready",true));b.error("NOT_IN_ROOM");
            b.command(PacketType.START_GAME);b.error("NOT_HOST");
            a.command(PacketType.START_GAME);a.error("NOT_READY");
            b.command(PacketType.READY,Map.of("ready",true));b.command(PacketType.READY,Map.of("ready",true));
            b.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).players().stream().anyMatch(v->v.playerId().equals(b.id)&&v.state().equals("READY")));
            a.command(PacketType.START_GAME);var match=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));b.take(PacketType.GAME_STARTED);
            c.command(PacketType.JOIN_ROOM,Map.of("roomId",id));c.error("ROOM_PLAYING");
            b.send(MatchEnvelopeCodec.wrap(match.matchId(),new Packet(PacketType.END_GAME,"{}")));b.error("NOT_HOST");
            a.send(MatchEnvelopeCodec.wrap(UUID.randomUUID(),new Packet(PacketType.END_GAME,"{}")));a.error("STALE_MATCH");
            assertEquals(1,f.server.activeMatchCount());
        }
    }
    @Test void concurrentStartCreatesOneMatchAndReplayReplacesEpoch() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B")) {
            create(a,4);join(b,a.room.roomId());ready(b);
            var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try {
                Future<?> one=executor.submit(()->{try{gate.await();a.command(PacketType.START_GAME);}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> two=executor.submit(()->{try{gate.await();a.command(PacketType.START_GAME);}catch(Exception e){throw new RuntimeException(e);}});
                gate.countDown();one.get();two.get();
            } finally {executor.shutdownNow();}
            var first=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));b.take(PacketType.GAME_STARTED);a.error("NOT_READY");
            assertEquals(1,f.server.activeMatchCount());assertEquals(1,f.server.snapshotWorkerCount());assertFull(a,first);
            var runtime=f.games.findGame(first.roomId()).orElseThrow();
            a.send(MatchEnvelopeCodec.wrap(first.matchId(),new Packet(PacketType.END_GAME,"{}")));
            var ended=LobbyJson.object(MatchEnvelopeCodec.unwrap(a.take(PacketType.GAME_OVER),first.matchId()).getJsonPayload());
            assertEquals("ABORTED",ended.get("reason"));assertNull(ended.get("winnerPlayerId"));assertEquals(List.of(),ended.get("rankings"));
            b.take(PacketType.GAME_OVER);waitFor(()->f.server.snapshotWorkerCount()==0);
            assertFalse(runtime.isThreadAlive());assertTrue(f.games.findGame(first.roomId()).isEmpty());
            var waiting=LobbyPacketCodec.decodeRoom(a.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).state().equals("WAITING")));
            assertTrue(waiting.players().stream().allMatch(p->p.state().equals("IN_ROOM")));assertEquals(0,f.server.activeMatchCount());
            ready(b);a.command(PacketType.START_GAME);var second=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));b.take(PacketType.GAME_STARTED);
            assertNotEquals(first.matchId(),second.matchId());assertFull(a,second);
            a.send(MatchEnvelopeCodec.wrap(first.matchId(),MovePacketCodec.encode(new MovePacketCodec.Input(99,Direction.RIGHT,true))));a.error("STALE_MATCH");
            a.command(PacketType.LEAVE_ROOM);a.take(PacketType.LEAVE_ROOM);b.take(PacketType.GAME_OVER);
            var transferred=LobbyPacketCodec.decodeRoom(b.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).hostId().equals(b.id)));
            assertEquals(1,transferred.players().size());assertEquals(0,f.server.activeMatchCount());waitFor(()->f.server.snapshotWorkerCount()==0);
            b.command(PacketType.LEAVE_ROOM);b.take(PacketType.LEAVE_ROOM);assertTrue(f.server.transport().getRoomManager().snapshot().isEmpty());
        }
    }
    @Test void disconnectAbortsMatchTransfersHostAndAllowsFreshPlayer() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B");var c=new Peer(f,"C")) {
            f.server.setSeamlessRecovery(false);
            String room=create(a,4);join(b,room);ready(b);a.command(PacketType.START_GAME);
            var old=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));b.take(PacketType.GAME_STARTED);a.close();
            b.take(PacketType.GAME_OVER);
            b.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).hostId().equals(b.id));
            waitFor(()->f.server.activeMatchCount()==0&&f.server.snapshotWorkerCount()==0);
            join(c,room);ready(c);b.command(PacketType.START_GAME);
            var fresh=DemoPacketCodec.decodeStarted(b.take(PacketType.GAME_STARTED));c.take(PacketType.GAME_STARTED);assertNotEquals(old.matchId(),fresh.matchId());
            assertFull(b,fresh);assertFull(c,fresh);
        }
    }
    @Test void seamlessRecoveryFreezesDisconnectedPlayerAndResumesOnReconnect() throws Exception {
        try(var f=new Fixture();var a=new Peer(f,"A");var b=new Peer(f,"B")) {
            f.server.setSeamlessRecovery(true);
            String room=create(a,4);join(b,room);ready(b);a.command(PacketType.START_GAME);
            var matchA=DemoPacketCodec.decodeStarted(a.take(PacketType.GAME_STARTED));
            var matchB=DemoPacketCodec.decodeStarted(b.take(PacketType.GAME_STARTED));
            assertFull(a,matchA);assertFull(b,matchB);
            int localIdA=matchA.playerId(a.id).orElseThrow();
            int localIdB=matchB.playerId(b.id).orElseThrow();

            // Client A drops physical connection
            a.close();

            // Match remains active, snapshot worker alive, server does not abort match immediately
            Thread.sleep(150);
            assertEquals(1,f.server.activeMatchCount());
            assertEquals(1,f.server.snapshotWorkerCount());

            // Peer B receives snapshots where player A is frozen (not removed from world)
            var update=b.until(p->p.getType()==PacketType.GAME_SNAPSHOT);
            var stateB=snapshot(update,matchB);
            assertFalse(stateB.getRemovedPlayerIds().contains(localIdA));

            // Client A reconnects with same playerId and reconnectToken via a fresh TCP connection
            var netA2 = new ClientNetwork();
            try {
                netA2.connect("127.0.0.1", f.server.port());
                netA2.send(SessionPacketCodec.reconnectRequest(a.id, a.token));

                // Reconnect handshake ACK
                Packet ack = netA2.receive(Duration.ofSeconds(5));
                assertNotNull(ack);
                assertEquals(PacketType.CONNECT_ACK, ack.getType());
                var decodedAck = SessionPacketCodec.decodeAcknowledgement(ack);
                assertEquals(a.id, decodedAck.playerId());

                // Server emits started metadata and requests full state
                Packet started = netA2.receive(Duration.ofSeconds(5));
                assertNotNull(started);
                assertEquals(PacketType.GAME_STARTED, started.getType());

                // Server emits full state sync or snapshot to sync client A
                Packet syncOrSnap = netA2.receive(Duration.ofSeconds(5));
                assertNotNull(syncOrSnap);
                assertTrue(syncOrSnap.getType() == PacketType.FULL_STATE_SYNC || syncOrSnap.getType() == PacketType.GAME_SNAPSHOT);

                // Client A sends movement command after reconnecting
                netA2.send(MatchEnvelopeCodec.wrap(matchA.matchId(),
                        MovePacketCodec.encode(new MovePacketCodec.Input(1, Direction.RIGHT, true))));

                // Peer B observes player A's input being processed (unfrozen!)
                b.until(p->{
                    if(p.getType()!=PacketType.GAME_SNAPSHOT) return false;
                    var snap=snapshot(p,matchB);
                    return snap.getPlayerChanges().stream().anyMatch(pl->pl.getId()==localIdA&&pl.getLastProcessedInput()>=1)
                        || snap.getPlayers().stream().anyMatch(pl->pl.getId()==localIdA&&pl.getLastProcessedInput()>=1);
                });

                // Both players can finish or end game gracefully
                netA2.send(MatchEnvelopeCodec.wrap(matchA.matchId(), new Packet(PacketType.END_GAME, "{}")));
                b.take(PacketType.GAME_OVER);
                waitFor(() -> f.server.activeMatchCount() == 0 && f.server.snapshotWorkerCount() == 0);
            } finally {
                netA2.close();
            }
        }
    }
    @Test void waitingHostLeaveAndRuntimeStartFailureKeepRoomUsable() throws Exception {
        var attempts=new AtomicInteger();
        var manager=new GameRuntimeManager(id->{if(attempts.getAndIncrement()==0)throw new IllegalStateException("test factory failure");return DemoGameServer.map();},(id,state)->GameplayExtension.none());
        try(var f=new Fixture(manager);var a=new Peer(f,"A");var b=new Peer(f,"B");var c=new Peer(f,"C")) {
            String room=create(a,4);join(b,room);a.command(PacketType.LEAVE_ROOM);a.take(PacketType.LEAVE_ROOM);
            b.until(p->p.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(p).hostId().equals(b.id));
            join(c,room);ready(c);b.command(PacketType.START_GAME);b.error("MATCH_START_FAILED");
            assertEquals(0,f.server.activeMatchCount());assertEquals(0,f.server.snapshotWorkerCount());
            assertEquals(SessionState.IN_ROOM,f.server.transport().getSessionManager().find(b.id).orElseThrow().getState());
            b.command(PacketType.START_GAME);var match=DemoPacketCodec.decodeStarted(b.take(PacketType.GAME_STARTED));c.take(PacketType.GAME_STARTED);assertFull(b,match);
        }
    }
    @Test void realClientControllersReturnToRoomAndReplayOnSameTcpConnections() throws Exception {
        var ui=Executors.newSingleThreadExecutor();var failure=new AtomicReference<Throwable>();var ended=new AtomicInteger();
        try(var f=new Fixture();var a=new DemoClientConnection(ui,m->{},s->{},failure::set);var b=new DemoClientConnection(ui,m->{},s->{},failure::set)) {
            var room=new AtomicReference<LobbyPacketCodec.RoomView>();
            a.setLobbyListener(p->{if(p.getType()==PacketType.ROOM_STATE) room.set(LobbyPacketCodec.decodeRoom(p));if(p.getType()==PacketType.GAME_OVER)ended.incrementAndGet();});
            b.setLobbyListener(p->{if(p.getType()==PacketType.GAME_OVER)ended.incrementAndGet();});
            a.connect("127.0.0.1",f.server.port(),"A");b.connect("127.0.0.1",f.server.port(),"B");
            a.send(LobbyPacketCodec.packet(PacketType.CREATE_ROOM,Map.of("roomName","actors","maxPlayers",2)));waitFor(()->room.get()!=null);
            b.send(LobbyPacketCodec.packet(PacketType.JOIN_ROOM,Map.of("roomId",room.get().roomId())));waitFor(()->room.get().players().size()==2);
            for(int round=0;round<2;round++) {
                b.send(LobbyPacketCodec.packet(PacketType.READY,Map.of("ready",true)));
                waitFor(()->room.get().players().stream().anyMatch(p->p.state().equals("READY")));
                a.send(new Packet(PacketType.START_GAME,"{}"));
                actorAwait(ui,()->a.movement()!=null&&b.movement()!=null&&a.movement().isInputReady()&&b.movement().isInputReady());
                var old=ui.submit(a::movement).get();
                ui.submit(()->a.movement().send(a.movement().prediction().predict(Direction.RIGHT,true))).get();
                actorAwait(ui,()->b.movement().world().getPlayers().get(a.localId()).getLastProcessedInput()==1);
                var match=ui.submit(()->a.context().matchId()).get();
                a.send(MatchEnvelopeCodec.wrap(match,new Packet(PacketType.END_GAME,"{}")));
                int expected=(round+1)*2;waitFor(()->ended.get()==expected);
                actorAwait(ui,()->a.context()==null&&b.context()==null&&old.isClosed());
                assertNull(failure.get());
            }
        } finally {ui.shutdown();assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void demoClientConnectionRecoversSeamlesslyAfterSocketDrop() throws Exception {
        var ui=Executors.newSingleThreadExecutor();
        var failureA=new AtomicReference<Throwable>();
        var failureB=new AtomicReference<Throwable>();
        var statusA=new CopyOnWriteArrayList<String>();
        try(var f=new Fixture();
            var a=new DemoClientConnection(ui,m->{},statusA::add,failureA::set);
            var b=new DemoClientConnection(ui,m->{},s->{},failureB::set)) {
            f.server.setSeamlessRecovery(true);
            a.setAutoReconnect(true);
            var room=new AtomicReference<LobbyPacketCodec.RoomView>();
            a.setLobbyListener(p->{if(p.getType()==PacketType.ROOM_STATE) room.set(LobbyPacketCodec.decodeRoom(p));});
            a.connect("127.0.0.1",f.server.port(),"A");
            b.connect("127.0.0.1",f.server.port(),"B");
            a.send(LobbyPacketCodec.packet(PacketType.CREATE_ROOM,Map.of("roomName","seamless","maxPlayers",2)));
            waitFor(()->room.get()!=null);
            b.send(LobbyPacketCodec.packet(PacketType.JOIN_ROOM,Map.of("roomId",room.get().roomId())));
            waitFor(()->room.get().players().size()==2);
            b.send(LobbyPacketCodec.packet(PacketType.READY,Map.of("ready",true)));
            waitFor(()->room.get().players().stream().anyMatch(p->p.state().equals("READY")));
            a.send(new Packet(PacketType.START_GAME,"{}"));
            actorAwait(ui,()->a.movement()!=null&&b.movement()!=null&&a.movement().isInputReady()&&b.movement().isInputReady());

            assertNull(failureA.get());
            assertNull(failureB.get());
            assertNotNull(a.reconnectToken());

            // Drop client A's active socket on server side (simulating sudden Wi-Fi loss)
            var sessionA = f.server.transport().getSessionManager().find(a.sessionId()).orElseThrow();
            sessionA.closeSocket();

            // Client A automatically detects drop, reconnects with same token, and gets full state sync!
            waitFor(() -> statusA.stream().anyMatch(s -> s.contains("Reconnected")));
            actorAwait(ui, () -> a.movement() != null && a.movement().isInputReady());

            // Movement still functions after reconnect
            ui.submit(() -> a.movement().send(a.movement().prediction().predict(Direction.RIGHT, true))).get();
            actorAwait(ui, () -> b.movement().world().getPlayers().get(a.localId()).getLastProcessedInput() >= 1);

            assertNull(failureA.get());
            assertNull(failureB.get());
        } finally {
            ui.shutdown();
            assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS));
        }
    }
    private static void actorAwait(ExecutorService ui,BooleanSupplier condition)throws Exception {waitFor(()->{try{return ui.submit(condition::getAsBoolean).get();}catch(Exception e){throw new RuntimeException(e);}});}
    private static void waitFor(BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()){if(System.nanoTime()>end)fail("Condition timed out");Thread.sleep(10);}}
    private static String create(Peer a,int capacity)throws Exception {a.command(PacketType.CREATE_ROOM,Map.of("roomName","Room "+a.id,"maxPlayers",capacity));a.room=LobbyPacketCodec.decodeRoom(a.take(PacketType.ROOM_STATE));return a.room.roomId();}
    private static void join(Peer p,String room)throws Exception {p.command(PacketType.JOIN_ROOM,Map.of("roomId",room));p.room=LobbyPacketCodec.decodeRoom(p.take(PacketType.ROOM_STATE));}
    private static void ready(Peer p)throws Exception {p.command(PacketType.READY,Map.of("ready",true));p.until(v->v.getType()==PacketType.ROOM_STATE&&LobbyPacketCodec.decodeRoom(v).players().stream().anyMatch(x->x.playerId().equals(p.id)&&x.state().equals("READY")));}
    private static GameStateUpdate snapshot(Packet p,MatchContext c) {try{return SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(p,c.matchId()));}catch(Exception e){throw new RuntimeException(e);}}
    private static void assertFull(Peer p,MatchContext c)throws Exception {p.until(v->v.getType()==PacketType.GAME_SNAPSHOT&&snapshot(v,c).isFullState());}
    private static final class Peer implements AutoCloseable {
        final ClientNetwork net=new ClientNetwork();final String id;final String token;final java.util.List<Packet> history=new ArrayList<>();LobbyPacketCodec.RoomView room;
        Peer(Fixture f,String name)throws Exception {
            net.connect("127.0.0.1",f.server.port());
            send(SessionPacketCodec.connectRequest(name));
            var ack=SessionPacketCodec.decodeAcknowledgement(take(PacketType.CONNECT_ACK));
            id=ack.playerId();
            token=ack.reconnectToken();
        }
        void send(Packet p)throws Exception {net.send(p);}void command(short type)throws Exception {command(type,Map.of());}
        void command(short type,Map<String,?> b)throws Exception {send(LobbyPacketCodec.packet(type,b));}
        Packet take(short type)throws Exception {return until(p->p.getType()==type);}
        Packet until(java.util.function.Predicate<Packet> predicate)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){Packet p=net.receive(Duration.ofMillis(100));if(p==null)continue;history.add(p);if(predicate.test(p))return p;}
            fail("Packet timeout; received: "+history.stream().map(p->PacketType.nameOf(p.getType())+":"+(p.getType()==PacketType.ERROR?p.getJsonPayload():"")).toList());return null;
        }
        void error(String code)throws Exception {assertEquals(code,SessionPacketCodec.errorCode(take(PacketType.ERROR)));}
        void drain(int millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){Packet p=net.receive(Duration.ofMillis(20));if(p!=null)history.add(p);}}
        public void close(){net.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final LobbyGameServer server;final GameRuntimeManager games;final Thread thread;final AtomicReference<Throwable> failure=new AtomicReference<>();
        Fixture()throws Exception {this(null);}Fixture(GameRuntimeManager games)throws Exception {
            this.games=games==null?new GameRuntimeManager(id->DemoGameServer.map(),(id,state)->GameplayExtension.none()):games;
            server=new LobbyGameServer(0,this.games);
            thread=new Thread(()->{try{server.start();}catch(Throwable e){failure.set(e);}},"lobby-test-server");thread.start();
        }
        public void close()throws Exception {
            server.close();thread.join(2000);assertFalse(thread.isAlive());assertNull(failure.get());assertTrue(server.failure().isEmpty(),()->server.failure().toString());
            assertTrue(server.transport().getConnectionFailure().isEmpty(),()->server.transport().getConnectionFailure().toString());
            assertEquals(0,server.activeMatchCount());assertEquals(0,server.snapshotWorkerCount());assertEquals(0,server.transport().getConnectionManager().count());
            assertTrue(server.transport().getRoomManager().snapshot().isEmpty());
        }
    }
}

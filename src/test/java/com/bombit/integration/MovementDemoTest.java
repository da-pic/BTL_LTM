package com.bombit.integration;

import com.bombit.client.app.GameClientConnection;
import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import com.bombit.server.app.DemoGameServer;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MovementDemoTest {
    @Test void threePlayerProfileStartsWithThreeUniqueIdentities() throws Exception {
        try(var fixture=new Fixture(3);var a=new Peer(fixture,"A");var b=new Peer(fixture,"B");var c=new Peer(fixture,"C")) {
            var peers=List.of(a,b,c);
            awaitPeers(peers,()->peers.stream().allMatch(peer->peer.ready(3)));
            assertEquals(3,peers.stream().map(Peer::localId).distinct().count());
            assertTrue(peers.stream().allMatch(peer->peer.context.matchId().equals(a.context.matchId())));
        }
    }

    @Test void serverShutdownClosesRealClientControllersAndReportsDisconnect() throws Exception {
        var ui=Executors.newSingleThreadExecutor();
        var errors=new java.util.concurrent.atomic.AtomicInteger();
        try(var fixture=new Fixture(2);
            var a=new GameClientConnection(ui,movement->{},status->{},error->errors.incrementAndGet());
            var b=new GameClientConnection(ui,movement->{},status->{},error->errors.incrementAndGet())) {
            a.setAutoReconnect(false);b.setAutoReconnect(false); // M1 has no resume admission.
            a.connect("127.0.0.1",fixture.server.port(),"A");b.connect("127.0.0.1",fixture.server.port(),"B");
            awaitActor(ui,()->a.movement()!=null&&b.movement()!=null&&a.movement().isInputReady()&&b.movement().isInputReady());
            fixture.close();
            awaitActor(ui,()->a.movement().isClosed()&&b.movement().isClosed()&&errors.get()==2);
        } finally {ui.shutdown();assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS));}
    }

    @Test void twoIndependentTcpClientsMoveStopAndCollideWithBoundary() throws Exception {
        try (var fixture = new Fixture(2); var a = new Peer(fixture, "A"); var b = new Peer(fixture, "B")) {
            awaitPeers(List.of(a,b), () -> a.ready(2) && b.ready(2));
            assertNotEquals(a.localId(), b.localId());
            assertEquals(a.context.matchId(), b.context.matchId());
            double before = a.player(a.localId()).getX();
            a.move(before < 100 ? Direction.RIGHT : Direction.LEFT, true);
            awaitPeers(List.of(a,b), () -> a.player(a.localId()).getLastProcessedInput() == 1
                    && b.player(a.localId()).getLastProcessedInput() == 1);
            assertEquals(7.5, Math.abs(a.player(a.localId()).getX()-before), .001);
            assertEquals(a.player(a.localId()).getX(), b.player(a.localId()).getX());
            a.move(Direction.DOWN, false);
            awaitPeers(List.of(a,b), () -> b.player(a.localId()).getLastProcessedInput() == 2);
            assertEquals(a.player(a.localId()).getX(), b.player(a.localId()).getX());
            double stopped = b.player(a.localId()).getX();
            a.resync(Long.MAX_VALUE); // Untrusted client tick must not prevent full recovery.
            awaitPeers(List.of(a,b), () -> a.syncs > 0 && b.syncs > 0);
            assertEquals(stopped, b.player(a.localId()).getX());
            assertFalse(b.player(a.localId()).isMoving());
            Peer left = a.player(a.localId()).getX() < 100 ? a : b;
            long finalSequence = left.sequence + 10;
            for (int n=0;n<10;n++) left.move(Direction.LEFT,true);
            awaitPeers(List.of(a,b), () -> a.player(left.localId()).getLastProcessedInput() == finalSequence
                    && b.player(left.localId()).getLastProcessedInput() == finalSequence);
            assertEquals(32, a.player(left.localId()).getX(), .001);
            assertEquals(32, b.player(left.localId()).getX(), .001);
        }
    }

    @Test void fourClientsRejectFifthAndContinueAfterOneDisconnects() throws Exception {
        try (var fixture = new Fixture(4); var a = new Peer(fixture,"A"); var b = new Peer(fixture,"B");
             var c = new Peer(fixture,"C"); var d = new Peer(fixture,"D")) {
            var all = List.of(a,b,c,d);
            awaitPeers(all, () -> all.stream().allMatch(peer -> peer.ready(4)));
            assertEquals(4, all.stream().map(Peer::localId).distinct().count());
            try (var fifth = new Peer(fixture,"Fifth")) {
                awaitPeers(List.of(fifth), () -> fifth.hasError("ROOM_FULL"));
                assertNull(fifth.context);
            }
            a.move(Direction.RIGHT,true);
            int removed = a.localId(); a.close();
            var remaining = List.of(b,c,d);
            awaitPeers(remaining, () -> remaining.stream().allMatch(peer -> !peer.world.getPlayers().containsKey(removed)));
            b.move(Direction.DOWN,true);
            awaitPeers(remaining, () -> remaining.stream().allMatch(peer -> peer.player(b.localId()).getLastProcessedInput() == 1));
            assertEquals(3, c.world.getPlayers().size());
        }
    }

    @Test void productionHandlersGateMatchAndResyncRateLimits() throws Exception {
        try (var fixture = new Fixture(2); var a = new Peer(fixture,"A")) {
            a.send(new Packet(PacketType.MOVE,"{}"));
            awaitPeers(List.of(a), () -> a.hasError("NOT_PLAYING"));
            try (var b = new Peer(fixture,"B")) {
                awaitPeers(List.of(a,b), () -> a.ready(2) && b.ready(2));
                a.send(MatchEnvelopeCodec.wrap(UUID.randomUUID(), MovePacketCodec.encode(new MovePacketCodec.Input(1,Direction.RIGHT,true))));
                awaitPeers(List.of(a,b), () -> a.hasError("STALE_MATCH"));
                assertEquals(0,a.player(a.localId()).getLastProcessedInput());
                long oldTick = a.world.getTick();
                assertEquals(ClientWorldState.Result.NEED_FULL_STATE, a.world.apply(GameStateUpdate.delta(oldTick+2,oldTick+1,
                        a.world.getWidth(),a.world.getHeight(),32,GameState.MatchState.RUNNING,false,List.of(),List.of(),List.of())));
                a.resync(oldTick); a.resync(oldTick);
                awaitPeers(List.of(a,b), () -> a.syncs>0 && b.syncs>0 && a.hasError("RATE_LIMITED"));
                assertFalse(a.world.needsFullState());
                assertTrue(a.world.getTick()>oldTick);
                for(int n=0;n<41;n++) a.move(Direction.DOWN,false);
                awaitPeers(List.of(a,b), () -> a.errors.stream().filter(error->error.contains("RATE_LIMITED")).count()>=2);
            }
        }
    }

    @Test void realClientControllerGetsFirstFullPredictsAndAutomaticallyResyncs() throws Exception {
        var ui = Executors.newSingleThreadExecutor();
        var errors = new AtomicReference<Throwable>();
        try (var fixture = new Fixture(2);
             var a = new GameClientConnection(ui, movement->{}, status->{}, errors::set);
             var b = new GameClientConnection(ui, movement->{}, status->{}, errors::set)) {
            a.connect("127.0.0.1",fixture.server.port(),"Controller A");
            b.connect("127.0.0.1",fixture.server.port(),"Controller B");
            awaitActor(ui, () -> a.movement()!=null && b.movement()!=null && a.movement().isInputReady() && b.movement().isInputReady());
            ui.submit(() -> a.movement().send(a.movement().prediction().predict(Direction.RIGHT,true))).get();
            awaitActor(ui, () -> b.movement().world().getPlayers().get(a.localId()).getLastProcessedInput()==1);
            long beforeGap=ui.submit(() -> {
                var world=a.movement().world(); long tick=world.getTick();
                var gap=GameStateUpdate.delta(tick+10_001,tick+10_000,world.getWidth(),world.getHeight(),32,
                        GameState.MatchState.RUNNING,false,List.of(),List.of(),List.of());
                try { a.movement().acceptPacket(MatchEnvelopeCodec.wrap(a.context().matchId(), SnapshotPacketCodec.encode(gap))); }
                catch(Exception error){throw new IllegalStateException(error);}
                return tick;
            }).get();
            // The synthetic gap is applied on the same actor after this marker.
            ui.submit(()->{}).get();
            awaitActor(ui, () -> a.movement().isInputReady() && !a.movement().world().needsFullState()
                    && a.movement().world().getTick()>beforeGap);
            assertNull(errors.get());
        } finally { ui.shutdown(); assertTrue(ui.awaitTermination(2,TimeUnit.SECONDS)); }
    }

    @Test void serverMapBlocksSoftBlocksAndInteriorWallsOverTcp() throws Exception {
        try(var fixture=new Fixture(2);var a=new Peer(fixture,"A");var b=new Peer(fixture,"B")) {
            var peers=List.of(a,b);
            awaitPeers(peers,()->a.ready(2)&&b.ready(2));
            Peer left=a.localId()==1?a:b;
            paced(left,Direction.DOWN,17,peers); // y=159.5, hitbox overlaps row 5.
            paced(left,Direction.RIGHT,20,peers); // Block at tile (5,5) starts at x=160.
            awaitPeers(peers,()->b.player(left.localId()).getLastProcessedInput()==left.sequence);
            double blockGap=160-(b.player(left.localId()).getX()+24);
            assertTrue(blockGap>=0&&blockGap<1,"Player must stop within one pixel before the block");
            paced(left,Direction.UP,9,peers); // Move above the block, still overlapping wall row 3.
            paced(left,Direction.RIGHT,20,peers);
            awaitPeers(peers,()->b.player(left.localId()).getLastProcessedInput()==left.sequence);
            double wallGap=256-(b.player(left.localId()).getX()+24);
            assertTrue(wallGap>=0&&wallGap<1,"Player must stop within one pixel before the wall");
            assertTrue(left.errors.isEmpty());
        }
    }

    private static void paced(Peer peer,Direction direction,int samples,List<Peer> peers)throws Exception {
        for(int n=0;n<samples;n++) {
            peer.move(direction,true);
            for(var observer:peers)observer.pump();
            Thread.sleep(50);
        }
    }

    @Test void emptyRoomCleansRuntimeAndNextRosterUsesNewMatchId() throws Exception {
        try (var fixture = new Fixture(2)) {
            UUID first;
            try (var a = new Peer(fixture,"A");var b = new Peer(fixture,"B")) {
                awaitPeers(List.of(a,b), ()->a.ready(2)&&b.ready(2)); first=a.context.matchId();
            }
            awaitCondition(()->fixture.server.context().isEmpty() && fixture.server.snapshotWorkerCount()==0);
            try(var a=new Peer(fixture,"New A");var b=new Peer(fixture,"New B")) {
                awaitPeers(List.of(a,b), ()->a.ready(2)&&b.ready(2));
                assertNotEquals(first,a.context.matchId());
            }
        }
    }

    private static void awaitActor(ExecutorService actor, BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end) { if(actor.submit(condition::getAsBoolean).get(1,TimeUnit.SECONDS)) return; Thread.sleep(5); }
        fail("Actor state did not converge");
    }
    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean()&&System.nanoTime()<end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static void awaitPeers(List<Peer> peers,BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<end) {
            for(var peer:peers) peer.pump();
            if(condition.getAsBoolean()) return;
        }
        fail("TCP replicas did not converge");
    }
    private static final class Peer implements AutoCloseable {
        final ClientNetwork network=new ClientNetwork();
        final ClientWorldState world=new ClientWorldState();
        final List<String> errors=new ArrayList<>();
        MatchContext context; String sessionId; long sequence; int syncs;
        Peer(Fixture fixture,String name)throws Exception {
            network.connect("127.0.0.1",fixture.server.port()); send(SessionPacketCodec.connectRequest(name));
        }
        void send(Packet packet)throws Exception { network.send(packet); }
        void move(Direction direction,boolean moving)throws Exception {
            send(MatchEnvelopeCodec.wrap(context.matchId(),MovePacketCodec.encode(new MovePacketCodec.Input(++sequence,direction,moving))));
        }
        void resync(long tick)throws Exception {
            send(MatchEnvelopeCodec.wrap(context.matchId(),ResyncPacketCodec.encode(new ResyncPacketCodec.Request(tick,ResyncPacketCodec.Reason.BASELINE_MISMATCH))));
        }
        void pump()throws Exception {
            Packet packet=network.receive(Duration.ofMillis(5)); if(packet==null)return;
            switch(packet.getType()) {
                case PacketType.CONNECT_ACK -> sessionId=SessionPacketCodec.decodeAcknowledgement(packet).playerId();
                case PacketType.GAME_STARTED -> { assertNotNull(sessionId); context=DemoPacketCodec.decodeStarted(packet); }
                case PacketType.GAME_SNAPSHOT,PacketType.FULL_STATE_SYNC -> {
                    assertNotNull(context,"Full snapshot must follow GAME_STARTED");
                    world.apply(SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(packet,context.matchId())));
                    if(packet.getType()==PacketType.FULL_STATE_SYNC)syncs++;
                }
                case PacketType.ERROR -> errors.add(packet.getJsonPayload());
                default -> { }
            }
        }
        int localId(){return context.playerId(sessionId).orElseThrow();}
        boolean ready(int players){return context!=null&&world.getPlayers().size()==players&&!world.needsFullState();}
        GameStateUpdate.PlayerData player(int id){return world.getPlayers().get(id);}
        boolean hasError(String code){return errors.stream().anyMatch(error->error.contains("\""+code+"\""));}
        @Override public void close(){network.close();}
    }
    private static final class Fixture implements AutoCloseable {
        final DemoGameServer server;
        final Thread thread;
        final AtomicReference<Throwable> error=new AtomicReference<>();
        Fixture(int players)throws Exception {
            server=new DemoGameServer(0,players);
            thread=new Thread(()->{try{server.start();}catch(Throwable failure){error.set(failure);}},"demo-integration-server");
            thread.start();
        }
        @Override public void close()throws Exception {
            server.close();thread.join(2_000);
            assertFalse(thread.isAlive());assertNull(error.get());assertTrue(server.failure().isEmpty(),()->server.failure().toString());
            assertTrue(server.transport().getConnectionFailure().isEmpty(),()->server.transport().getConnectionFailure().toString());
            assertEquals(0,server.snapshotWorkerCount());
            assertEquals(0,server.transport().getConnectionManager().count());
        }
    }
}

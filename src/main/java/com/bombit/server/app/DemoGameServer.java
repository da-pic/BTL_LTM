package com.bombit.server.app;

import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import com.bombit.server.dispatch.RequestRejectedException;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.network.*;
import com.bombit.server.session.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** M1 composition root: one fixed room, fixed roster of 2..4, no bomb/item extension. */
public final class DemoGameServer implements AutoCloseable, GameEventSink {
    public static final String ROOM_ID = "00000000-0000-4000-8000-000000000001";
    private final Server server;
    private final int expectedPlayers;
    // M1 là fixture movement-only: giữ factory legacy và speed 150, không tự thêm bom/item
    // hoặc gắn preset PUBLIC Classic 80 px/s vào demo có luật khác.
    private final GameRuntimeManager games = new GameRuntimeManager(id -> map(), (id, state) -> GameplayExtension.none());
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Long> resyncBarrier = new AtomicReference<>();
    private volatile GameRuntime runtime;
    private volatile MatchContext context;
    private volatile boolean closed;

    private static final class Member {
        final Session session;
        volatile boolean ready;
        long window = System.nanoTime(), lastResync;
        int moves;
        Member(Session session) { this.session = session; }
        synchronized boolean moveAllowed() {
            long now = System.nanoTime();
            if (now - window >= TimeUnit.SECONDS.toNanos(1)) { window = now; moves = 0; }
            return ++moves <= 40;
        }
        synchronized boolean resyncAllowed() {
            long now = System.nanoTime();
            if (lastResync != 0 && now - lastResync < TimeUnit.SECONDS.toNanos(1)) return false;
            lastResync = now; return true;
        }
    }

    public DemoGameServer(int port, int expectedPlayers) throws IOException {
        if (expectedPlayers < 2 || expectedPlayers > 4) throw new IllegalArgumentException("Choose 2..4 players");
        this.expectedPlayers = expectedPlayers;
        server = new Server(port);
        server.setGameObserver(this);
        server.setConnectedHandler(this::connected);
        var movement = new MovementHandler(games, id -> server.getSessionManager().find(id).orElse(null));
        server.getRouter().registerHandler(PacketType.MOVE, (id, packet) -> {
            Member member = member(id);
            if (!member.ready) throw new RequestRejectedException("NOT_READY");
            if (!member.moveAllowed()) throw new RequestRejectedException("RATE_LIMITED");
            Packet body = unwrap(packet);
            MovePacketCodec.decode(body);
            try { movement.handle(id, body); }
            catch (IllegalStateException unavailable) { throw new RequestRejectedException("INPUT_QUEUE_FULL"); }
        });
        server.getRouter().registerHandler(PacketType.REQUEST_FULL_STATE, (id, packet) -> {
            Member member = member(id);
            ResyncPacketCodec.decode(unwrap(packet));
            if (!member.resyncAllowed()) throw new RequestRejectedException("RATE_LIMITED");
            GameRuntime game = runtime;
            var latest = game.createUpdate();
            resyncBarrier.set(latest == null ? -1L : latest.getTick());
            game.requestFullState();
        });
    }

    private Member member(String id) {
        Member member = members.get(id);
        if (member == null || member.session.getState() != SessionState.PLAYING || runtime == null) {
            throw new RequestRejectedException("NOT_PLAYING");
        }
        return member;
    }
    private Packet unwrap(Packet packet) {
        MatchContext match = context;
        if (match == null) throw new RequestRejectedException("NOT_PLAYING");
        try { return MatchEnvelopeCodec.unwrap(packet, match.matchId()); }
        catch (IOException invalid) { throw new RequestRejectedException("STALE_MATCH"); }
    }

    private synchronized void connected(Session session) {
        if (closed) throw new RequestRejectedException("MATCH_FAILED");
        var socket = session.getSocket();
        if (socket == null || socket.isClosed() || session.getState() != SessionState.IN_LOBBY) return;
        if (members.size() >= expectedPlayers) throw new RequestRejectedException("ROOM_FULL");
        if (runtime != null) throw new RequestRejectedException("ROOM_PLAYING");
        members.put(session.getPlayerId(), new Member(session));
        session.setRoomId(ROOM_ID); session.setState(SessionState.IN_ROOM);
        for (Member member : List.copyOf(members.values())) send(member, DemoPacketCodec.waiting(ROOM_ID, members.size(), expectedPlayers));
        if (members.size() != expectedPlayers) return;
        var roster = members.values().stream().map(m -> m.session).sorted(Comparator.comparing(Session::getPlayerId)).toList();
        var spawns = new ArrayList<PlayerSpawn>();
        var identities = new ArrayList<MatchContext.PlayerIdentity>();
        var names = new HashMap<String, String>();
        int[][] positions = {{32,32}, {480,32}, {32,416}, {480,416}};
        for (int n = 0; n < roster.size(); n++) {
            Session player = roster.get(n);
            spawns.add(new PlayerSpawn(player.getPlayerId(), n + 1, positions[n][0], positions[n][1], 24, 24, 150));
            identities.add(new MatchContext.PlayerIdentity(player.getPlayerId(), n + 1));
            names.put(player.getPlayerId(), player.getUsername());
        }
        try {
            GameRuntime game = games.startGame(ROOM_ID, spawns);
            runtime = game;
            context = new MatchContext(ROOM_ID, game.matchId(), identities);
            resyncBarrier.set(null);
            Packet started = DemoPacketCodec.started(context, names);
            for (Member member : List.copyOf(members.values())) {
                member.session.setState(SessionState.PLAYING);
                send(member, started); // All metadata is queued before the only snapshot consumer starts.
            }
            Thread worker = new Thread(() -> broadcast(game), "Demo-Snapshot-" + game.matchId());
            workers.add(worker); worker.start();
        } catch (RuntimeException startFailure) {
            games.stopGame(ROOM_ID); runtime = null; context = null;
            for (Member member : members.values()) {
                member.session.setState(SessionState.IN_ROOM);
                send(member, SessionPacketCodec.error("MATCH_START_FAILED"));
            }
            failure.compareAndSet(null, startFailure);
        }
    }

    private void broadcast(GameRuntime game) {
        try {
            while (!closed && runtime == game && game.isRunning()) {
                var update = game.pollUpdate(200, TimeUnit.MILLISECONDS);
                if (update == null) continue;
                if (runtime != game) break;
                Long barrier = resyncBarrier.get();
                boolean sync = update.isFullState() && barrier != null && update.getTick() > barrier
                        && resyncBarrier.compareAndSet(barrier, null);
                Packet encoded = sync ? SnapshotPacketCodec.encodeFullSync(update) : SnapshotPacketCodec.encode(update);
                Packet scoped = MatchEnvelopeCodec.wrap(game.matchId(), encoded);
                for (Member member : List.copyOf(members.values())) {
                    if (send(member, scoped) && update.isFullState()) member.ready = true;
                }
            }
            if (!closed && runtime == game && game.failure() != null) throw new IllegalStateException("Runtime failed", game.failure());
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Throwable error) {
            failure.compareAndSet(null, error);
            for (Member member : List.copyOf(members.values())) {
                send(member, SessionPacketCodec.error("MATCH_FAILED"));
                server.getSessionManager().hardDrop(member.session.getPlayerId());
            }
        } finally { workers.remove(Thread.currentThread()); }
    }

    private boolean send(Member member, Packet packet) {
        try {
            if (member.session.getOutboundSender() == null) return false;
            member.session.getOutboundSender().send(member.session, OutboundMessage.fromPacket(packet));
            return true;
        } catch (IllegalStateException closedWriter) { return false; }
    }

    private synchronized void disconnected(Session session) {
        if (members.remove(session.getPlayerId()) == null) return;
        GameRuntime game = runtime;
        if (game != null) {
            game.playerId(session.getPlayerId()).ifPresent(id -> game.submit(new PlayerDisconnectedCommand(id, true)));
        }
        session.setRoomId(null);
        if (members.isEmpty()) {
            runtime = null; context = null; resyncBarrier.set(null);
            games.stopGame(ROOM_ID);
        } else if (game == null) {
            for (Member member : members.values()) send(member, DemoPacketCodec.waiting(ROOM_ID, members.size(), expectedPlayers));
        }
    }
    @Override public void onPlayerDisconnected(Session session) { disconnected(session); }
    @Override public void onPlayerHardDropped(Session session) { disconnected(session); }
    @Override public void onPlayerReconnected(Session session) { /* No reconnect admission in M1. */ }

    public void start() throws IOException { server.start(); }
    public int port() { return server.getPort(); }
    public Server transport() { return server; }
    public Optional<MatchContext> context() { return Optional.ofNullable(context); }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure.get()); }
    public int snapshotWorkerCount() { return workers.size(); }
    @Override public void close() {
        synchronized (this) { closed = true; }
        server.close(); games.close();
        for (Thread worker : List.copyOf(workers)) {
            worker.interrupt();
            if (worker != Thread.currentThread()) {
                try { worker.join(2_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    /** Legacy movement-only fixture. Lobby gameplay uses game.map.ArenaGenerator instead. */
    public static GameMap map() {
        var map = new GameMap(17, 15, 32);
        for (int y = 0; y < 15; y++) for (int x = 0; x < 17; x++) {
            if (x == 0 || y == 0 || x == 16 || y == 14 || (x == 8 && y >= 3 && y <= 11)) {
                map.setTile(x, y, TileType.HARD_WALL);
            }
        }
        map.setTile(5, 5, TileType.BREAKABLE_BLOCK);
        map.setTile(11, 9, TileType.BREAKABLE_BLOCK);
        return map;
    }
}

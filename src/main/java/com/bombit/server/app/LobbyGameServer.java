package com.bombit.server.app;

import com.bombit.common.dto.MatchContext;
import com.bombit.common.dto.MatchResult;
import com.bombit.common.dto.MapSelection;
import com.bombit.common.dto.MapTheme;
import com.bombit.common.protocol.*;
import com.bombit.server.chat.ChatMessage;
import com.bombit.server.dispatch.RequestRejectedException;
import com.bombit.server.game.core.*;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.network.*;
import com.bombit.server.room.*;
import com.bombit.server.session.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Serializes room transitions; each match has its own runtime and snapshot consumer. */
public final class LobbyGameServer implements AutoCloseable, GameEventSink {
    private final Server server;
    private final RoomManager rooms;
    private final GameRuntimeManager games;
    private final PlayerMovementRules movementRules;
    private final Map<String, Match> matches = new HashMap<>();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private boolean closed;

    private static final class Match {
        final GameRuntime game;
        final MatchContext context;
        final List<Session> roster;
        final Set<String> initialized = ConcurrentHashMap.newKeySet();
        final Map<String, Long> windows = new HashMap<>(), resyncs = new HashMap<>();
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String,Long> plantIds=new HashMap<>(),plantWindows=new HashMap<>();
        final Map<String,Integer> plantCounts=new HashMap<>();
        volatile boolean active = true;
        volatile Long barrier;
        Match(GameRuntime game, MatchContext context, List<Session> roster) {
            this.game=game; this.context=context; this.roster=roster;
        }
    }

    public LobbyGameServer(int port) throws IOException {
        this(port,new BombRules(Long.getLong("bombit.bomb.fuseMillis",3000L),
                Integer.getInteger("bombit.bomb.range",1),Integer.getInteger("bombit.bomb.quota",1)));
    }
    /** Demo balance is configurable; each room still receives a fresh extension/world. */
    public LobbyGameServer(int port,BombRules rules) throws IOException {
        this(port,rules,MatchRules.demo());
    }
    public LobbyGameServer(int port,BombRules rules,MatchRules matchRules) throws IOException {
        this(port,new GameRuntimeManager(id->ArenaGenerator.create(),
                (room,state,players)->new MatchGameplayExtension(state,players,rules,matchRules,
                        new com.bombit.server.game.item.RandomItemSpawnPolicy(matchRules.dropChance(),new Random()),event->{},result->{})));
    }
    public LobbyGameServer(int port, GameRuntimeManager games) throws IOException {
        this(port,games,PlayerMovementRules.defaults());
    }
    public LobbyGameServer(int port,GameRuntimeManager games,PlayerMovementRules movementRules)throws IOException{
        this.movementRules=Objects.requireNonNull(movementRules);
        movementRules.spawn("validation",1,ArenaGenerator.SPAWNS.getFirst(),ArenaGenerator.TILE_SIZE);
        this.games=games; server=new Server(port); rooms=server.getRoomManager();
        server.setGameObserver(this);
        server.setConnectedHandler(session -> { synchronized (this) { send(session, list()); } });
        short[] types={PacketType.LIST_ROOMS,PacketType.CREATE_ROOM,PacketType.JOIN_ROOM,PacketType.LEAVE_ROOM,
                PacketType.READY,PacketType.START_GAME,PacketType.SET_MAP_SELECTION,PacketType.CHAT,PacketType.END_GAME,
                PacketType.MOVE,PacketType.PLANT_BOMB,PacketType.REQUEST_FULL_STATE};
        for (short type:types) server.getRouter().registerHandler(type,this::handle);
    }
    private synchronized void handle(String id, Packet packet) {
        Session session=server.getSessionManager().find(id).orElseThrow(() -> reject("NOT_CONNECTED"));
        if (closed || session.getState()==SessionState.CLOSED || session.getState()==SessionState.DISCONNECTED)
            throw reject("NOT_CONNECTED");
        switch(packet.getType()) {
            case PacketType.LIST_ROOMS -> { fields(packet); send(session,list()); }
            case PacketType.CREATE_ROOM -> {
                var b=LobbyPacketCodec.request(packet,Set.of("roomName","maxPlayers"),Set.of("mode"));
                if(b.containsKey("mode")&&!LobbyPacketCodec.text(b,"mode",32).equals("STANDARD")) throw reject("MODE_NOT_SUPPORTED");
                if(session.getRoomId()!=null) throw reject("ALREADY_IN_ROOM");
                if(rooms.snapshot().size()>=32) throw reject("ROOM_LIMIT");
                rooms.createRoom(LobbyPacketCodec.text(b,"roomName",64),session,LobbyPacketCodec.integer(b,"maxPlayers",2,4));
                publishLists();
            }
            case PacketType.JOIN_ROOM -> {
                var b=LobbyPacketCodec.request(packet,Set.of("roomId"));
                if(session.getRoomId()!=null) throw reject("ALREADY_IN_ROOM");
                Room room=rooms.find(LobbyPacketCodec.roomId(b));
                if(room==null) throw reject("ROOM_NOT_FOUND");
                if(room.getState()!=Room.State.WAITING) throw reject("ROOM_PLAYING");
                if(!rooms.joinRoom(room.getRoomId(),session)) throw reject("ROOM_FULL");
                publishLists();
            }
            case PacketType.LEAVE_ROOM -> {
                roomFields(session,packet,Set.of());String roomId=requireRoom(session).getRoomId();
                leave(session);send(session,LobbyPacketCodec.packet(PacketType.LEAVE_ROOM,Map.of("roomId",roomId,"left",true)));
            }
            case PacketType.READY -> {
                var b=roomFields(session,packet,Set.of("ready"));
                if(rooms.setReady(session,LobbyPacketCodec.bool(b,"ready")).isEmpty()) throw reject("INVALID_ROOM_STATE");
                publishLists();
            }
            case PacketType.START_GAME -> { roomFields(session,packet,Set.of()); startMatch(session); }
            case PacketType.SET_MAP_SELECTION -> {
                var b=roomFields(session,packet,Set.of("mapSelection"));
                Room room=requireRoom(session);
                if(!room.getHostPlayerId().equals(session.getPlayerId())) throw reject("NOT_HOST");
                if(room.getState()!=Room.State.WAITING) throw reject("ROOM_PLAYING");
                MapSelection selection;
                try { selection=MapSelection.valueOf(LobbyPacketCodec.text(b,"mapSelection",16)); }
                catch(IllegalArgumentException invalid) { throw reject("INVALID_MAP_SELECTION"); }
                if(!room.setMapSelection(selection)) throw reject("ROOM_PLAYING");
                rooms.publishRoomState(room);publishLists();
            }
            case PacketType.CHAT -> {
                var b=roomFields(session,packet,Set.of("message")); requireRoom(session);
                if(!server.getChatHandler().enqueue(new ChatMessage(session.getRoomId(),id,session.getUsername(),
                        LobbyPacketCodec.text(b,"message",256)))) throw reject("CHAT_QUEUE_FULL");
            }
            case PacketType.END_GAME -> {
                Room room=requireRoom(session); Match match=requireMatch(session,packet);
                fields(unwrap(packet,match));
                if(!room.getHostPlayerId().equals(id)) throw reject("NOT_HOST");
                endMatch(room); publishLists();
            }
            case PacketType.MOVE -> {
                Match match=requireMatch(session,packet);
                if(!match.initialized.contains(id)) throw reject("NOT_READY");
                long now=System.nanoTime();
                if(now-match.windows.getOrDefault(id,0L)>=TimeUnit.SECONDS.toNanos(1)) {
                    match.windows.put(id,now); match.counts.put(id,0);
                }
                int count=match.counts.merge(id,1,Integer::sum);
                if(count>40) throw reject("RATE_LIMITED");
                try { new MovementHandler(games,key -> server.getSessionManager().find(key).orElse(null)).handle(id,unwrap(packet,match)); }
                catch(IllegalStateException full) { throw reject("INPUT_QUEUE_FULL"); }
            }
            case PacketType.REQUEST_FULL_STATE -> {
                Match match=requireMatch(session,packet); ResyncPacketCodec.decode(unwrap(packet,match));
                long now=System.nanoTime();
                if(now-match.resyncs.getOrDefault(id,0L)<TimeUnit.SECONDS.toNanos(1)) throw reject("RATE_LIMITED");
                match.resyncs.put(id,now);
                var latest=match.game.createUpdate(); match.barrier=latest==null?-1L:latest.getTick();
                match.game.requestFullState();
            }
            case PacketType.PLANT_BOMB -> {
                Match match=requireMatch(session,packet);
                long request=BombPacketCodec.decodePlant(unwrap(packet,match));
                if(!match.initialized.contains(id)) throw reject("NOT_READY");
                if(request<=match.plantIds.getOrDefault(id,0L)) return;
                long now=System.nanoTime();
                Long window=match.plantWindows.get(id);
                if(window==null||now-window>=TimeUnit.SECONDS.toNanos(1)) {
                    match.plantWindows.put(id,now);match.plantCounts.put(id,0);
                }
                if(match.plantCounts.merge(id,1,Integer::sum)>5) throw reject("RATE_LIMITED");
                int player=match.context.playerId(id).orElseThrow(()->reject("NOT_PLAYING"));
                if(!match.game.submit(new PlantBombCommand(player))) throw reject("INPUT_QUEUE_FULL");
                match.plantIds.put(id,request);
            }
            default -> throw reject("INVALID_PACKET");
        }
    }
    private Map<String,Object> roomFields(Session session,Packet p,Set<String> required) {
        var body=LobbyPacketCodec.request(p,required,Set.of("roomId"));
        Room room=requireRoom(session);
        if(body.containsKey("roomId")&&!room.getRoomId().equals(LobbyPacketCodec.roomId(body))) throw reject("NOT_IN_ROOM");
        return body;
    }
    private static void fields(Packet p) { LobbyPacketCodec.request(p,Set.of()); }
    private Room requireRoom(Session s) {
        Room room=s.getRoomId()==null?null:rooms.find(s.getRoomId());
        if(room==null || !room.getPlayersSnapshot().contains(s)) throw reject("NOT_IN_ROOM");
        return room;
    }
    private Match requireMatch(Session s,Packet p) {
        Match match=matches.get(s.getRoomId());
        if(match==null || !match.active || s.getState()!=SessionState.PLAYING) throw reject("NOT_PLAYING");
        unwrap(p,match); return match;
    }
    private static Packet unwrap(Packet p,Match m) {
        try { return MatchEnvelopeCodec.unwrap(p,m.context.matchId()); }
        catch(IOException e) { throw reject("STALE_MATCH"); }
    }
    private void startMatch(Session host) {
        Room room=requireRoom(host);
        if(!room.getHostPlayerId().equals(host.getPlayerId())) throw reject("NOT_HOST");
        if(!room.canStartGame()) throw reject("NOT_READY");
        var roster=room.getPlayersSnapshot(); var spawns=new ArrayList<PlayerSpawn>();
        var identities=new ArrayList<MatchContext.PlayerIdentity>(); var names=new HashMap<String,String>();
        for(int n=0;n<roster.size();n++) {
            var cell=ArenaGenerator.SPAWNS.get(n);
            int size=ArenaGenerator.TILE_SIZE;
            Session s=roster.get(n); spawns.add(movementRules.spawn(s.getPlayerId(),n+1,cell,size));
            identities.add(new MatchContext.PlayerIdentity(s.getPlayerId(),n+1)); names.put(s.getPlayerId(),s.getUsername());
        }
        GameRuntime game;
        try { game=games.startGame(room.getRoomId(),spawns); }
        catch(RuntimeException error) { games.stopGame(room.getRoomId()); throw reject("MATCH_START_FAILED"); }
        MapTheme theme=resolveTheme(room.getMapSelection());
        Match match=new Match(game,new MatchContext(room.getRoomId(),game.matchId(),theme,identities),roster);
        matches.put(room.getRoomId(),match); room.setState(Room.State.PLAYING);
        roster.forEach(s -> s.setState(SessionState.PLAYING));
        Packet started=DemoPacketCodec.started(match.context,names);
        for(Session s:roster) { if(!match.active) break; send(s,started); }
        if(!match.active) return; // A failed writer may synchronously remove the roster.
        Thread worker=new Thread(() -> pump(match),"Lobby-Snapshot-"+game.matchId());
        workers.add(worker); worker.start(); rooms.publishRoomState(room); publishLists();
    }
    private static MapTheme resolveTheme(MapSelection selection) {
        if(selection==MapSelection.RANDOM) {
            MapTheme[] themes=MapTheme.values();
            return themes[ThreadLocalRandom.current().nextInt(themes.length)];
        }
        return MapTheme.valueOf(selection.name());
    }
    private void pump(Match match) {
        try {
            while(match.active && match.game.isRunning()) {
                var update=match.game.pollUpdate(200,TimeUnit.MILLISECONDS); if(update==null) continue;
                Long barrier=match.barrier;
                boolean sync=update.isFullState() && barrier!=null && update.getTick()>barrier;
                if(sync) match.barrier=null;
                Packet p=MatchEnvelopeCodec.wrap(match.context.matchId(),sync?SnapshotPacketCodec.encodeFullSync(update):SnapshotPacketCodec.encode(update));
                if(!match.active) break;
                for(Session s:match.roster) if(match.active && send(s,p) && update.isFullState()) match.initialized.add(s.getPlayerId());
                // Publish notices after their authoritative snapshot, using the same per-session writer queue.
                for(var notice:update.getGameNotices()) {
                    Packet scoped=MatchEnvelopeCodec.wrap(match.context.matchId(),GameplayPacketCodec.notice(update.getTick(),notice,match.context));
                    for(Session s:match.roster) if(match.active) send(s,scoped);
                }
                if(update.getMatchFrame()!=null&&update.getMatchFrame().result()!=null){
                    synchronized(this){
                        Room room=rooms.find(match.context.roomId());
                        if(room!=null&&matches.get(room.getRoomId())==match){endMatch(room,update.getMatchFrame().result());publishLists();}
                    }
                    break;
                }
            }
            if(match.active && match.game.failure()!=null) throw new IllegalStateException("Runtime failed",match.game.failure());
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(Throwable e) {
            failure.compareAndSet(null,e);
            synchronized(this) { Room room=rooms.find(match.context.roomId()); if(room!=null && matches.get(room.getRoomId())==match) { endMatch(room); publishLists(); } }
        } finally { workers.remove(Thread.currentThread()); }
    }
    private void endMatch(Room room) {
        endMatch(room,null);
    }
    private void endMatch(Room room,MatchResult outcome) {
        Match match=matches.remove(room.getRoomId()); if(match==null) return;
        match.active=false; games.stopGame(room.getRoomId());
        room.setState(Room.State.WAITING);
        for(Session s:room.getPlayersSnapshot()) if(s.getState()==SessionState.PLAYING) s.setState(SessionState.IN_ROOM);
        try {
            var latest=match.game.createUpdate();
            if(outcome==null&&latest!=null&&latest.getMatchFrame()!=null)outcome=latest.getMatchFrame().result();
            if(outcome==null)outcome=new MatchResult(latest==null?0:latest.getTick(),null,MatchResult.Reason.ABORTED,List.of());
            Packet ended=MatchEnvelopeCodec.wrap(match.context.matchId(),GameplayPacketCodec.result(outcome));
            match.roster.forEach(s -> send(s,ended));
        } catch(IOException e) { throw new IllegalStateException(e); }
        rooms.publishRoomState(room);
    }
    private void leave(Session s) {
        Room room=s.getRoomId()==null?null:rooms.find(s.getRoomId());
        if(room!=null) endMatch(room);
        rooms.leaveRoom(s); publishLists();
    }
    private Packet list() {
        return LobbyPacketCodec.roomList(rooms.snapshot().values().stream().filter(r -> !r.isEmpty())
                .sorted(Comparator.comparing(Room::getRoomId)).map(RoomManager::view).toList());
    }
    private void publishLists() {
        Packet p=list();
        for(Session s:server.getSessionManager().snapshot().values()) if(s.getState()!=SessionState.CONNECTED) send(s,p);
    }
    private static boolean send(Session s,Packet p) {
        if(s.getState()==SessionState.CLOSED || s.getState()==SessionState.DISCONNECTED || s.getOutboundSender()==null) return false;
        try { s.getOutboundSender().send(s,OutboundMessage.fromPacket(p)); return true; }
        catch(IllegalStateException unavailable) { return false; }
    }
    private static RequestRejectedException reject(String code) { return new RequestRejectedException(code); }
    private volatile boolean seamlessRecovery = false;
    public void setSeamlessRecovery(boolean enabled) { this.seamlessRecovery = enabled; }
    public boolean isSeamlessRecovery() { return seamlessRecovery; }

    @Override public synchronized void onPlayerDisconnected(Session s) {
        String roomId = s.getRoomId();
        Match match = roomId == null ? null : matches.get(roomId);
        if (seamlessRecovery && match != null && match.active) {
            int playerNum = match.context.playerId(s.getPlayerId()).orElse(-1);
            if (playerNum > 0) {
                match.game.submit(new PlayerDisconnectedCommand(playerNum, false));
            }
            return;
        }
        leave(s);
    }
    @Override public synchronized void onPlayerHardDropped(Session s) {
        String roomId = s.getRoomId();
        Match match = roomId == null ? null : matches.get(roomId);
        if (match != null && match.active) {
            int playerNum = match.context.playerId(s.getPlayerId()).orElse(-1);
            if (playerNum > 0) {
                match.game.submit(new PlayerDisconnectedCommand(playerNum, true));
            }
        }
        leave(s);
    }
    @Override public synchronized void onPlayerReconnected(Session s) {
        String roomId = s.getRoomId();
        Match match = roomId == null ? null : matches.get(roomId);
        if (match != null && match.active) {
            int playerNum = match.context.playerId(s.getPlayerId()).orElse(-1);
            if (playerNum > 0) {
                match.game.submit(new PlayerReconnectedCommand(playerNum));
            }
            var roster = match.roster;
            var names = new HashMap<String, String>();
            for (Session p : roster) names.put(p.getPlayerId(), p.getUsername());
            Packet started = DemoPacketCodec.started(match.context, names);
            send(s, started);
            var latest = match.game.createUpdate();
            match.barrier = latest == null ? -1L : latest.getTick();
            match.game.requestFullState();
            match.initialized.add(s.getPlayerId());
        }
    }
    public void start() throws IOException { server.start(); }
    public int port() { return server.getPort(); }
    public Server transport() { return server; }
    public synchronized int activeMatchCount() { return matches.size(); }
    public int snapshotWorkerCount() { return workers.size(); }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure.get()); }
    @Override public void close() {
        synchronized(this) { if(closed) return; closed=true; for(Room room:List.copyOf(rooms.snapshot().values())) endMatch(room); }
        server.close(); games.close();
        for(Thread worker:List.copyOf(workers)) { worker.interrupt(); if(worker!=Thread.currentThread()) try { worker.join(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}

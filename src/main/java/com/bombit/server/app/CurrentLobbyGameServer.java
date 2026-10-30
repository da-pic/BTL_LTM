package com.bombit.server.app;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.chat.ChatMessage;
import com.bombit.server.game.core.*;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.replication.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.network.*;
import com.bombit.server.room.*;
import com.bombit.server.session.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.bombit.common.protocol.JsonV3.*;

/** Lobby v3 tuần tự hóa room/stream; simulation vẫn chỉ do GameLoop sở hữu. */
final class CurrentLobbyGameServer implements AutoCloseable,GameEventSink {
    private final Server server;
    private final RoomManager rooms;
    private final GameRuntimeManager games;
    private final ModeRegistry registry;
    private final PlayerMovementRules movement;
    private final Map<String,Match> matches=new HashMap<>();
    private final Map<String,Completed> completed=new HashMap<>();
    private final Set<Thread> workers=ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    private boolean closed,seamless=true;
    private record Completed(MatchStartMetadata metadata,Packet result) { }
    private static final class Request {final BombType type;Packet result;Request(BombType type){this.type=type;}}
    private static final class Window {long started=System.nanoTime();int count;}
    private static final class Match {
        final GameRuntime game;final MatchContext context;final List<Session> roster;
        final Map<String,RecipientStream> streams=new HashMap<>();
        final Map<String,OutboundSender> owners=new HashMap<>();
        final Map<String,Long> recoveryBarriers=new HashMap<>();long nextRecovery;
        final Map<String,Long> waitingSince=new HashMap<>(),highWater=new HashMap<>();
        final Map<String,LinkedHashMap<Long,Request>> requests=new HashMap<>();
        final Map<String,Window> windows=new HashMap<>();
        final Map<String,Set<String>> sentRewindResults=new HashMap<>();
        volatile boolean active=true;
        Match(GameRuntime game,MatchContext context,List<Session> roster){this.game=game;this.context=context;this.roster=List.copyOf(roster);}
    }
    CurrentLobbyGameServer(int port,GameRuntimeManager games,PlayerMovementRules movement)throws IOException{
        this.games=Objects.requireNonNull(games);registry=games.modeRegistry().orElseThrow();this.movement=Objects.requireNonNull(movement);
        var rule=registry.resolvePublic(GameMode.CLASSIC).effectiveRules().common();
        if(rule.hitboxPixels()!=movement.hitboxSize()||rule.initialSpeed()!=movement.initialSpeed())throw new IllegalArgumentException("Movement differs from registry");
        games.enableWorldFrames();server=new Server(port,true);rooms=server.getRoomManager();server.setGameObserver(this);
        server.setConnectedHandler(s->{synchronized(this){send(s,list());}});
        for(short type:new short[]{PacketType.CREATE_ROOM,PacketType.JOIN_ROOM,PacketType.LIST_ROOMS,PacketType.READY,PacketType.START_GAME,PacketType.LEAVE_ROOM,PacketType.SET_MAP_SELECTION,
                PacketType.SET_MATCH_CONFIGURATION,PacketType.CHAT,PacketType.MOVE,PacketType.PLANT_BOMB,PacketType.REWIND,PacketType.REQUEST_FULL_STATE,PacketType.END_GAME})server.getRouter().registerHandler(type,this::handle);
    }
    private static ProtocolRejection reject(String code){return new ProtocolRejection(code);}
    private synchronized void handle(String id,Packet p){try{handleChecked(id,p);}catch(ModeConfigurationException e){throw reject(e.code().name());}}
    private void handleChecked(String id,Packet p){
        var s=server.getSessionManager().find(id).orElseThrow(()->reject("NOT_CONNECTED"));
        if(closed||s.getState()==SessionState.CLOSED||s.getState()==SessionState.DISCONNECTED)throw reject("NOT_CONNECTED");
        if(Set.of(PacketType.MOVE,PacketType.PLANT_BOMB,PacketType.REWIND,PacketType.REQUEST_FULL_STATE,PacketType.END_GAME).contains(p.getType())){gameplay(s,p);return;}
        var m=object(p.getJsonPayload(),4096);
        switch(p.getType()){
            case PacketType.LIST_ROOMS->{fields(m);send(s,list());}
            case PacketType.CREATE_ROOM->{
                if(!m.keySet().containsAll(Set.of("roomName","maxPlayers"))||m.keySet().stream().anyMatch(k->!Set.of("roomName","maxPlayers","gameMode","matchFormat","mapSelection").contains(k)))throw new IllegalArgumentException("Create fields");
                if(s.getRoomId()!=null)throw reject("ALREADY_IN_ROOM");if(rooms.snapshot().size()>=32)throw reject("ROOM_LIMIT");
                GameMode mode;try{mode=GameMode.valueOf(m.containsKey("gameMode")?text(m,"gameMode",32):"CLASSIC");}catch(IllegalArgumentException e){throw reject("MODE_NOT_SUPPORTED");}
                var config=registry.resolvePublic(mode,MetadataV3Codec.matchFormat(m,mode));registry.validateClientFeatures(config,s.features());int capacity=(int)integer(m,"maxPlayers",2,4);
                if(config.isTeamMatch()&&capacity!=4)throw reject("INVALID_CONFIGURATION");
                var theme=m.containsKey("mapSelection")?MapSelection.valueOf(text(m,"mapSelection",16)):MapSelection.RANDOM;
                var room=rooms.createRoom(text(m,"roomName",64).strip(),s,capacity,config);completed.remove(id);room.setMapSelection(theme);rooms.publishRoomState(room);publishLists();
            }
            case PacketType.JOIN_ROOM->{fields(m,"roomId");if(s.getRoomId()!=null)throw reject("ALREADY_IN_ROOM");var room=rooms.find(uuid(m,"roomId"));if(room==null)throw reject("ROOM_NOT_FOUND");waiting(room);
                registry.validateClientFeatures(room.configuration().orElseThrow(),s.features());if(!rooms.joinRoom(room.getRoomId(),s))throw reject("ROOM_FULL");completed.remove(id);publishLists();}
            case PacketType.READY->{fields(m,"roomId","configurationRevision","ready");var room=requireRoom(s,m);if(!room.setReady(s,bool(m,"ready"),integer(m,"configurationRevision",1,Long.MAX_VALUE)))throw reject("INVALID_ROOM_STATE");rooms.publishRoomState(room);publishLists();}
            case PacketType.START_GAME->{fields(m,"roomId","configurationRevision");var room=requireRoom(s,m);checkRevision(room,integer(m,"configurationRevision",1,Long.MAX_VALUE));start(s,room);}
            case PacketType.SET_MATCH_CONFIGURATION->{fields(m,"roomId","expectedRevision","configuration");var room=requireRoom(s,m);host(s,room);waiting(room);checkRevision(room,integer(m,"expectedRevision",1,Long.MAX_VALUE));
                var raw=map(m.get("configuration"));var normalized=new LinkedHashMap<>(raw);var mode=GameMode.valueOf(text(raw,"gameMode",32));
                normalized.putIfAbsent("matchFormat",MetadataV3Codec.matchFormat(raw,mode).name());
                var c=fields(normalized,"gameMode","matchFormat","rulesVersion","presetId");var config=registry.resolvePublic(text(c,"gameMode",32),text(c,"rulesVersion",64),text(c,"presetId",64)).withFormat(MetadataV3Codec.matchFormat(c,mode));
                for(var peer:room.getPlayersSnapshot())registry.validateClientFeatures(config,peer.features());room.changeConfiguration(config);rooms.publishRoomState(room);publishLists();}
            case PacketType.SET_MAP_SELECTION->{fields(m,"roomId","mapSelection");var room=requireRoom(s,m);host(s,room);waiting(room);room.setMapSelection(MapSelection.valueOf(text(m,"mapSelection",16)));rooms.publishRoomState(room);publishLists();}
            case PacketType.LEAVE_ROOM->{fields(m,"roomId");var room=requireRoom(s,m);String roomId=room.getRoomId();leave(s);send(s,packet(PacketType.LEAVE_ROOM,body("roomId",roomId,"left",true)));}
            case PacketType.CHAT->{fields(m,"roomId","message");var room=requireRoom(s,m);if(!server.getChatHandler().enqueue(new ChatMessage(room.getRoomId(),id,s.getUsername(),text(m,"message",256))))throw reject("CHAT_QUEUE_FULL");}
            default->throw reject("INVALID_PACKET");
        }
    }
    private Room requireRoom(Session s,Map<String,Object> m){var room=s.getRoomId()==null?null:rooms.find(s.getRoomId());if(room==null||!room.getPlayersSnapshot().contains(s)||!room.getRoomId().equals(uuid(m,"roomId")))throw reject("NOT_IN_ROOM");return room;}
    private static void host(Session s,Room r){if(!s.getPlayerId().equals(r.getHostPlayerId()))throw reject("NOT_HOST");}
    private static void waiting(Room r){if(r.getState()!=Room.State.WAITING)throw reject("ROOM_PLAYING");}
    private static void checkRevision(Room r,long revision){if(r.configurationRevision()!=revision)throw reject("CONFIGURATION_STALE");}
    private void start(Session host,Room room){
        host(host,room);waiting(room);if(!room.canStartGame())throw reject("NOT_READY");var roster=room.getPlayersSnapshot();var config=room.configuration().orElseThrow();
        registry.validatePublicStart(config,roster.size());for(var s:roster)registry.validateClientFeatures(config,s.features());
        var spawns=new ArrayList<PlayerSpawn>();var members=new ArrayList<MatchStartMetadata.Member>();var identities=new ArrayList<MatchContext.PlayerIdentity>();
        if(config.gameMode()==GameMode.SHADOW_RELAY){
            var setup=com.bombit.server.game.mode.shadow.ShadowMatchSetup.prepare(RoomManager.viewV3(room));
            spawns.addAll(setup.spawns());members.addAll(setup.roster().players());identities.addAll(setup.roster().identities());
        }else for(int i=0;i<roster.size();i++){var s=roster.get(i);int player=i+1;
            var spawn=config.isTeamMatch()?TeamMatchPolicy.spawnForSlot(player):ArenaGenerator.SPAWNS.get(i);
            spawns.add(movement.spawn(s.getPlayerId(),player,spawn,32));identities.add(new MatchContext.PlayerIdentity(s.getPlayerId(),player));members.add(new MatchStartMetadata.Member(s.getPlayerId(),player,player,s.getUsername(),config.teamForSlot(player)));}
        GameRuntime game;try{game=games.startGame(room.getRoomId(),spawns,config);}catch(RuntimeException e){games.stopGame(room.getRoomId());throw reject("MATCH_START_FAILED");}
        var themes=MapTheme.values();var theme=room.getMapSelection()==MapSelection.RANDOM?themes[ThreadLocalRandom.current().nextInt(themes.length)]:MapTheme.valueOf(room.getMapSelection().name());
        var context=new MatchContext(room.getRoomId(),game.matchId(),theme,identities);var match=new Match(game,context,roster);matches.put(room.getRoomId(),match);room.setState(Room.State.PLAYING);
        for(var s:roster){s.setState(SessionState.PLAYING);completed.remove(s.getPlayerId());var meta=new MatchStartMetadata(context,config,room.configurationRevision(),members,new StreamContext(context.playerId(s.getPlayerId()).orElseThrow(),1,0,0));
            match.streams.put(s.getPlayerId(),streamFor(meta));match.owners.put(s.getPlayerId(),s.getOutboundSender());match.waitingSince.put(s.getPlayerId(),System.nanoTime());send(s,MetadataV3Codec.started(meta));}
        var worker=new Thread(()->pump(match),"V3-Snapshot-"+game.matchId());workers.add(worker);worker.start();rooms.publishRoomState(room);publishLists();
    }
    private Match requireMatch(Session s,Packet p){var m=matches.get(s.getRoomId());if(m==null||!m.active||s.getState()!=SessionState.PLAYING)throw reject("NOT_PLAYING");return m;}
    private void rate(Match m,String id,String kind,int limit){var w=m.windows.computeIfAbsent(id+kind,k->new Window());long now=System.nanoTime();if(now-w.started>=1_000_000_000L){w.started=now;w.count=0;}if(++w.count>limit)throw reject("RATE_LIMITED");}
    private void gameplay(Session s,Packet p){
        var match=requireMatch(s,p);var stream=match.streams.get(s.getPlayerId());long epoch=match.game.timelineEpoch();
        var inner=MatchEnvelopeV3Codec.unwrapCommand(p,match.context.matchId(),epoch);var m=object(inner.getJsonPayload(),4096);
        if(p.getType()==PacketType.END_GAME){fields(m);var room=rooms.find(s.getRoomId());host(s,room);finish(room,null);publishLists();return;}
        if(p.getType()==PacketType.REQUEST_FULL_STATE){fields(m,"viewEpoch","lastAppliedTick","reason");integer(m,"lastAppliedTick",-1,Long.MAX_VALUE);if(!Set.of("COLD_START","BASELINE_MISMATCH","RECONNECT").contains(text(m,"reason",32)))throw new IllegalArgumentException("Bad resync reason");
            if(integer(m,"viewEpoch",1,Long.MAX_VALUE)!=stream.metadata().stream().viewEpoch())throw reject("STALE_CONTEXT");
            String key=s.getPlayerId()+"RESYNC";var w=match.windows.computeIfAbsent(key,k->new Window());if(w.count>0&&System.nanoTime()-w.started<1_000_000_000L)throw reject("RATE_LIMITED");w.started=System.nanoTime();w.count=1;reset(match,s);return;}
        if(stream==null||!stream.active()||stream.metadata().stream().timelineEpoch()!=epoch||s.getOutboundSender()!=match.owners.get(s.getPlayerId()))throw reject("NOT_READY");
        int player=match.context.playerId(s.getPlayerId()).orElseThrow();
        if(p.getType()==PacketType.REWIND){
            fields(m,"requestId");if(match.game.configuration().orElseThrow().gameMode()!=GameMode.PARADOX)throw reject("UNSUPPORTED_ACTION");
            rate(match,s.getPlayerId(),"REWIND",5);
            if(!match.game.submit(new RewindCommand(player,integer(m,"requestId",1,Long.MAX_VALUE),epoch),epoch))throw reject("INPUT_QUEUE_FULL");return;
        }
        if(p.getType()==PacketType.MOVE){fields(m,"sequence","direction","moving");rate(match,s.getPlayerId(),"MOVE",40);long sequence=integer(m,"sequence",1,Long.MAX_VALUE);var dir=Direction.valueOf(text(m,"direction",8));if(!match.game.submit(bool(m,"moving")?new MoveCommand(player,dir,sequence):new StopMovingCommand(player,dir,sequence),epoch))throw reject("INPUT_QUEUE_FULL");return;}
        fields(m,"requestId","bombType");long request=integer(m,"requestId",1,Long.MAX_VALUE);var type=BombType.valueOf(text(m,"bombType",8));var cache=match.requests.computeIfAbsent(s.getPlayerId(),k->new LinkedHashMap<>());var known=cache.get(request);
        if(known!=null){if(known.type!=type)throw reject("REQUEST_ID_CONFLICT");if(known.result!=null)sendScoped(match,s,known.result);return;}
        if(request<=match.highWater.getOrDefault(s.getPlayerId(),0L))throw reject("REQUEST_TOO_OLD");if(cache.values().stream().filter(r->r.result==null).count()>=64)throw reject("RATE_LIMITED");
        match.highWater.put(s.getPlayerId(),request);var entry=new Request(type);cache.put(request,entry);String error=null;
        try{rate(match,s.getPlayerId(),"PLANT",5);}catch(ProtocolRejection e){error=e.code();}if(error==null&&type!=BombType.NORMAL&&match.game.configuration().orElseThrow().gameMode()!=GameMode.SHADOW_RELAY)error="UNSUPPORTED_ACTION";
        if(error==null&&!match.game.submit(new PlantBombCommand(player,request,type),epoch))error="INPUT_QUEUE_FULL";
        if(error!=null){entry.result=plantResult(request,type,false,error,null,null);sendScoped(match,s,entry.result);prune(cache);}
    }
    private static Packet plantResult(long request,BombType type,boolean accepted,String error,Integer bomb,Long tick){return packet(PacketType.PLANT_RESULT,body("requestId",request,"bombType",type.name(),"status",accepted?"ACCEPTED":"REJECTED","errorCode",error,"bombId",bomb,"processedTick",tick));}
    private static void prune(LinkedHashMap<Long,Request> cache){long completed=cache.values().stream().filter(r->r.result!=null).count();for(var i=cache.entrySet().iterator();completed>64&&i.hasNext();)if(i.next().getValue().result!=null){i.remove();completed--;}}
    private static RecipientStream streamFor(MatchStartMetadata metadata){return metadata.configuration().gameMode()==GameMode.PARADOX?new ParadoxRecipientStream(metadata):metadata.configuration().gameMode()==GameMode.SHADOW_RELAY?new ShadowRecipientStream(metadata):new ClassicRecipientStream(metadata);}
    private void reset(Match match,Session s){
        var old=match.streams.get(s.getPlayerId());var metadata=old.metadata();var context=metadata.stream();
        // Người offline có thể bỏ lỡ nhiều rewind: lấy epoch hiện tại trước khi gửi metadata recovery.
        metadata=metadata.withStream(new StreamContext(context.recipientPlayerId(),context.viewEpoch(),match.game.timelineEpoch(),context.authorityEpoch()));
        var stream=streamFor(metadata);old.close();stream.reset(match.game.lastCompletedTick());
        match.streams.put(s.getPlayerId(),stream);match.owners.put(s.getPlayerId(),s.getOutboundSender());
        match.waitingSince.put(s.getPlayerId(),System.nanoTime());send(s,MetadataV3Codec.started(stream.metadata()));
    }
    private void pump(Match match){
        try{while(match.active&&match.game.isRunning()){
            var frame=match.game.pollWorldFrame(200,TimeUnit.MILLISECONDS);if(frame==null)continue;
            synchronized(this){if(!match.active)break;
                // Frame đã dequeue trước một rewind có thể còn đang chờ monitor: không gửi nhánh cũ.
                if(frame.paradox()!=null&&frame.paradox().timelineEpoch()!=match.game.timelineEpoch())continue;
                for(var result:frame.plantResults()){var member=match.roster.stream().filter(s->match.context.playerId(s.getPlayerId()).orElse(-1)==result.playerId()).findFirst().orElseThrow();var cache=match.requests.get(member.getPlayerId());var entry=cache==null?null:cache.get(result.requestId());
                    if(entry!=null){entry.result=plantResult(result.requestId(),result.bombType(),result.accepted(),result.errorCode(),result.bombId(),frame.snapshot().getTick());prune(cache);}}
                for(var s:match.roster){String id=s.getPlayerId();var stream=match.streams.get(id);if(s.getState()!=SessionState.PLAYING||stream==null||s.getOutboundSender()!=match.owners.get(id)||s.getSocket()==null)continue;
                    if(frame.paradox()!=null&&frame.paradox().timelineEpoch()>stream.metadata().stream().timelineEpoch()){
                        var old=stream.metadata();var c=old.stream();stream.close();
                        stream=streamFor(old.withStream(new StreamContext(c.recipientPlayerId(),Math.addExact(c.viewEpoch(),1),frame.paradox().timelineEpoch(),c.authorityEpoch())));
                        match.streams.put(id,stream);match.waitingSince.put(id,System.nanoTime());
                        // Request đặt bom chưa hoàn thành ở nhánh cũ phải có kết quả chấm dứt.
                        var requests=match.requests.get(id);if(requests!=null)for(var entry:requests.entrySet())if(entry.getValue().result==null)
                            entry.getValue().result=plantResult(entry.getKey(),entry.getValue().type,false,"STALE_TIMELINE",null,null);
                        if(!send(s,MetadataV3Codec.started(stream.metadata()))){stream.close();continue;}
                    }
                    Long waiting=match.waitingSince.get(id);if(waiting!=null&&System.nanoTime()-waiting>4_000_000_000L){server.getSessionManager().handleUnexpectedDisconnect(id,s.getSocket());continue;}var recoveryBarrier=match.recoveryBarriers.get(id);
                    // Chỉ full sau khi game thread đã xử lý reconnect: không gửi view từ input backlog cũ.
                    if(recoveryBarrier!=null&&frame.recoveryAcks().getOrDefault(stream.metadata().stream().recipientPlayerId(),0L)<recoveryBarrier)continue;
                    var update=stream.build(frame);if(update==null)continue;
                    var captured=s.getOutboundSender();var socket=s.getSocket();var encoded=MatchEnvelopeV3Codec.update(match.context.matchId(),stream.metadata().stream(),SnapshotV6Codec.encode(update,frame.epochOrigin(),match.context));
                    if(socket!=s.getSocket()||captured!=s.getOutboundSender()||captured!=match.owners.get(id))continue;if(!send(s,encoded)){stream.close();continue;}stream.commit(update);match.waitingSince.remove(id);match.recoveryBarriers.remove(id);
                    for(var notice:update instanceof ShadowSnapshotV6?update.update().getGameNotices():frame.snapshot().getGameNotices())sendScoped(match,s,GameplayV3Codec.notice(frame.snapshot().getTick(),notice,match.context,frame.epochOrigin()));
                    for(var result:frame.plantResults())if(result.playerId()==stream.metadata().stream().recipientPlayerId()){var cache=match.requests.get(id);var entry=cache==null?null:cache.get(result.requestId());if(entry!=null&&entry.result!=null)sendScoped(match,s,entry.result);}
                    var delivered=match.sentRewindResults.computeIfAbsent(id,k->new LinkedHashSet<>());
                    for(var result:frame.rewindResults())if(result.playerId()==stream.metadata().stream().recipientPlayerId()&&result.timelineEpoch()==stream.metadata().stream().timelineEpoch()){
                        String key=result.requestId()+":"+result.outcome()+":"+result.serverTick();
                        if(!delivered.contains(key)&&sendScoped(match,s,ParadoxCodec.result(result)))delivered.add(key);
                    }
                    while(delivered.size()>256)delivered.remove(delivered.iterator().next());
                }
                var result=frame.snapshot().getMatchFrame().result();if(result!=null||frame.snapshot().getMatchFrame().teamResult()!=null||frame.shadow()!=null&&frame.shadow().result()!=null){var room=rooms.find(match.context.roomId());if(room!=null)finish(room,result);publishLists();break;}
            }
        }if(match.active&&match.game.failure()!=null)throw new IllegalStateException("Runtime failed",match.game.failure());}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Throwable e){failure.compareAndSet(null,e);synchronized(this){var room=rooms.find(match.context.roomId());if(room!=null)finish(room,null);}}
        finally{workers.remove(Thread.currentThread());}
    }
    private boolean sendScoped(Match match,Session s,Packet inner){var stream=match.streams.get(s.getPlayerId());return stream!=null&&send(s,MatchEnvelopeV3Codec.update(match.context.matchId(),stream.metadata().stream(),inner));}
    private void finish(Room room,MatchResult result){
        if(room==null)return;var match=matches.remove(room.getRoomId());if(match==null)return;match.active=false;games.stopGame(room.getRoomId());
        // Kết quả thuộc timeline cuối, kể cả recipient đã offline từ timeline trước.
        for(var s:match.roster){
            var old=match.streams.get(s.getPlayerId());var metadata=old.metadata();var context=metadata.stream();
            if(context.timelineEpoch()!=match.game.timelineEpoch()){
                old.close();var current=streamFor(metadata.withStream(new StreamContext(context.recipientPlayerId(),
                        Math.addExact(context.viewEpoch(),1),match.game.timelineEpoch(),context.authorityEpoch())));
                match.streams.put(s.getPlayerId(),current);
                if(s.getState()!=SessionState.DISCONNECTED)send(s,MetadataV3Codec.started(current.metadata()));
            }
        }
        Packet resultPacket;
        if(match.game.configuration().orElseThrow().gameMode()==GameMode.SHADOW_RELAY){
            var last=match.game.latestWorldFrame();var shadow=last==null?null:last.shadow();
            var teamResult=shadow==null?null:shadow.result();
            if(teamResult==null)teamResult=new ShadowFrame.Result(Math.max(0,match.game.lastCompletedTick()),null,ShadowFrame.Reason.ABORTED,shadow==null?0:shadow.scoreA(),shadow==null?0:shadow.scoreB());
            resultPacket=packet(PacketType.GAME_OVER,ShadowModeCodec.result(teamResult));
        }else if(match.game.configuration().orElseThrow().isTeamMatch()){
            var last=match.game.createUpdate();var team=last==null||last.getMatchFrame()==null?null:last.getMatchFrame().teamResult();
            if(team==null)team=new TeamMatchResult(Math.max(0,match.game.lastCompletedTick()),null,TeamMatchResult.Reason.ABORTED);
            resultPacket=packet(PacketType.GAME_OVER,GameplayV3Codec.teamResultBody(team,match.game.configuration().orElseThrow().gameMode()));
        }else{
            if(result==null){var last=match.game.createUpdate();result=last!=null&&last.getMatchFrame()!=null?last.getMatchFrame().result():null;}
            if(result==null)result=new MatchResult(Math.max(0,match.game.lastCompletedTick()),null,MatchResult.Reason.ABORTED,List.of());
            resultPacket=packet(PacketType.GAME_OVER,GameplayV3Codec.resultBody(result,match.game.configuration().orElseThrow().gameMode()));
        }
        room.setState(Room.State.WAITING);
        for(var s:room.getPlayersSnapshot())if(s.getState()==SessionState.PLAYING)s.setState(SessionState.IN_ROOM);
        for(var s:match.roster){var stream=match.streams.get(s.getPlayerId());if(s.getState()==SessionState.DISCONNECTED)completed.put(s.getPlayerId(),new Completed(stream.metadata(),resultPacket));
            var cache=match.requests.get(s.getPlayerId());if(cache!=null)for(var r:cache.entrySet())if(r.getValue().result==null){r.getValue().result=plantResult(r.getKey(),r.getValue().type,false,"MATCH_NOT_RUNNING",null,null);sendScoped(match,s,r.getValue().result);}
            sendScoped(match,s,resultPacket);stream.close();}
        rooms.publishRoomState(room);
    }
    private void leave(Session s){var room=s.getRoomId()==null?null:rooms.find(s.getRoomId());if(room!=null)finish(room,null);completed.remove(s.getPlayerId());rooms.leaveRoom(s);publishLists();}
    private Packet list(){return MetadataV3Codec.rooms(rooms.snapshot().values().stream().filter(r->!r.isEmpty()).sorted(Comparator.comparing(Room::getRoomId)).map(RoomManager::viewV3).toList(),registry.descriptors());}
    private void publishLists(){var p=list();for(var s:server.getSessionManager().snapshot().values())if(s.getState()!=SessionState.CONNECTED)send(s,p);}
    private static boolean send(Session s,Packet p){if(s.getState()==SessionState.DISCONNECTED||s.getState()==SessionState.CLOSED||s.getOutboundSender()==null)return false;try{s.getOutboundSender().send(s,OutboundMessage.fromPacket(p));return true;}catch(IllegalStateException e){return false;}}
    public synchronized void onPlayerDisconnected(Session s){var match=matches.get(s.getRoomId());if(match==null||!match.active)return;if(!seamless){leave(s);return;}match.owners.remove(s.getPlayerId());int id=match.context.playerId(s.getPlayerId()).orElse(-1);if(id<=0||!match.game.submit(new PlayerDisconnectedCommand(id)))finish(rooms.find(s.getRoomId()),null);}
    public synchronized void onPlayerHardDropped(Session s){completed.remove(s.getPlayerId());String room=s.getRoomId();var match=room==null?null:matches.get(room);if(!seamless){leave(s);return;}
        if(match!=null&&match.active){match.owners.remove(s.getPlayerId());int id=match.context.playerId(s.getPlayerId()).orElse(-1);if(id<=0||!match.game.submit(new PlayerDisconnectedCommand(id,true)))finish(rooms.find(room),null);}
        rooms.leaveRoom(s);if(room!=null&&rooms.find(room)==null&&match!=null){match.active=false;matches.remove(room);games.stopGame(room);}publishLists();}
    public synchronized void onPlayerReconnected(Session s){
        var ended=completed.get(s.getPlayerId());if(seamless&&ended!=null){s.setState(s.getRoomId()==null?SessionState.IN_LOBBY:SessionState.IN_ROOM);var old=ended.metadata();var ctx=old.stream();var meta=old.withStream(new StreamContext(ctx.recipientPlayerId(),Math.addExact(ctx.viewEpoch(),1),ctx.timelineEpoch(),ctx.authorityEpoch()));
            // Enqueue không chứng minh client đã nhận GAME_OVER: giữ cache cho lần rớt kế tiếp.
            // Cache được xóa khi hết grace, rời/đổi phòng hoặc bắt đầu trận mới.
            completed.put(s.getPlayerId(),new Completed(meta,ended.result()));
            if(!send(s,MetadataV3Codec.started(meta))||!send(s,MatchEnvelopeV3Codec.update(meta.identity().matchId(),meta.stream(),ended.result())))throw new IllegalStateException("Result recovery unavailable");var room=s.getRoomId()==null?null:rooms.find(s.getRoomId());if(room!=null)rooms.publishRoomState(room);send(s,list());return;}
        var match=s.getRoomId()==null?null:matches.get(s.getRoomId());if(!seamless||match==null||!match.active)throw new SessionRecoveryRejectedException("No match to recover");int id=match.context.playerId(s.getPlayerId()).orElseThrow();
        long barrier=Math.addExact(match.nextRecovery,1);match.nextRecovery=barrier;
        if(!match.game.submit(new PlayerReconnectedCommand(id,barrier)))throw new IllegalStateException("Recovery queue full");
        match.recoveryBarriers.put(s.getPlayerId(),barrier);reset(match,s);rooms.publishRoomState(rooms.find(s.getRoomId()));send(s,list());
    }
    public void start()throws IOException{server.start();}public Server transport(){return server;}
    public synchronized int activeMatchCount(){return matches.size();}public int snapshotWorkerCount(){return workers.size();}public Optional<Throwable> failure(){return Optional.ofNullable(failure.get());}
    public synchronized void setSeamlessRecovery(boolean value){seamless=value;}public synchronized boolean isSeamlessRecovery(){return seamless;}
    public void close(){synchronized(this){if(closed)return;closed=true;for(var room:List.copyOf(rooms.snapshot().values()))finish(room,null);}server.close();games.close();for(var worker:List.copyOf(workers)){worker.interrupt();if(worker!=Thread.currentThread())try{worker.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}

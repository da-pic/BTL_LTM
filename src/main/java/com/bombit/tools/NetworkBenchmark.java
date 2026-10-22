package com.bombit.tools;

import com.bombit.client.game.movement.ClientWorldState;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.MatchContext;
import com.bombit.common.protocol.*;
import com.bombit.metrics.LatencyMonitor;
import com.bombit.server.app.LobbyGameServer;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.MatchRules;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Headless protocol-2 workload runner; never changes authoritative world outside the normal packets. */
public final class NetworkBenchmark {
    public static final List<String> COLUMNS=List.of("run_id","scenario","timestamp_utc","label","revision","scope","host","port",
            "runner_clients","match_players","client_index","mode","warmup_seconds","window_seconds","probe_interval_ms",
            "tx_packets","rx_packets","tx_bytes","rx_bytes","tx_bytes_per_second","rx_bytes_per_second",
            "ping_sent","rtt_samples","rtt_mean_ms","rtt_min_ms","rtt_p95_ms","rtt_max_ms","pending_ping_end",
            "moves_sent","move_ack_samples","move_ack_mean_ms","move_ack_p95_ms","pending_move_end",
            "full_snapshots","delta_snapshots","snapshot_bytes","stale_snapshots","resync_requests",
            "bombs_observed","explosions_observed","plant_ack_ms","status","error","cleanup_ok","java_version","os","processors");
    public record Config(boolean local,String host,int port,int clients,int expected,String room,String mode,int seconds,int warmup,
                         Path csv,String label,String revision,String runId){
        public Config{
            if(!Set.of(1,2,4).contains(clients)||!Set.of(1,2,4).contains(expected)||clients>expected
                    ||seconds<1||seconds>300||warmup<0||warmup>30||port<0||port>65535
                    ||!Set.of("transport","idle","movement","bombs","suite").contains(mode))throw new IllegalArgumentException("Invalid benchmark configuration");
            if(!mode.equals("transport")&&!mode.equals("suite")&&expected<2)throw new IllegalArgumentException("Gameplay requires 2 or 4 expected clients; use transport for 1");
            if(mode.equals("suite")&&(!local||room!=null))throw new IllegalArgumentException("suite requires --local");
            if(room!=null&&(local||clients!=1))throw new IllegalArgumentException("Distributed guest uses --clients 1 and a remote room");
            if(local&&clients!=expected&&!mode.equals("suite"))throw new IllegalArgumentException("Local run must own every client");
            if(local&&!host.equals("127.0.0.1"))throw new IllegalArgumentException("Local runs use 127.0.0.1");
            if(!local&&port==0)throw new IllegalArgumentException("Remote server port must be positive");
            if(mode.equals("transport")&&clients!=expected)throw new IllegalArgumentException("Transport count is runner client count");
            if((mode.equals("bombs")||mode.equals("suite"))&&seconds<4)throw new IllegalArgumentException("Bomb measurements need at least 4 seconds");
            if(room!=null&&!UUID.fromString(room).toString().equals(room))throw new IllegalArgumentException("Invalid room ID");
        }
    }
    private NetworkBenchmark(){}
    public static Config parse(String[] args){
        var options=new HashMap<String,String>();boolean local=false;
        for(int i=0;i<args.length;i++){
            if(args[i].equals("--local")){if(local)throw new IllegalArgumentException("Duplicate --local");local=true;continue;}
            if(!Set.of("--host","--port","--clients","--expected-clients","--room-id","--mode","--seconds","--warmup","--csv","--label","--revision","--run-id").contains(args[i])
                    ||i+1>=args.length||options.putIfAbsent(args[i],args[++i])!=null)throw new IllegalArgumentException("Unknown, missing or duplicate option");
        }
        int clients=Integer.parseInt(options.getOrDefault("--clients","2"));
        return new Config(local,options.getOrDefault("--host","127.0.0.1"),Integer.parseInt(options.getOrDefault("--port","8888")),clients,
                Integer.parseInt(options.getOrDefault("--expected-clients",""+clients)),options.get("--room-id"),options.getOrDefault("--mode","movement"),
                Integer.parseInt(options.getOrDefault("--seconds","10")),Integer.parseInt(options.getOrDefault("--warmup","2")),
                Path.of(options.getOrDefault("--csv","target/network-benchmark.csv")),options.getOrDefault("--label","unlabelled"),
                options.getOrDefault("--revision","unknown"),options.getOrDefault("--run-id",UUID.randomUUID().toString()));
    }
    public static void main(String[] args)throws Exception{
        if(Arrays.asList(args).contains("--help")){
            System.out.println("NetworkBenchmark --local --mode suite --seconds 5 --warmup 1 --csv results.csv\n"
                    +"Remote: --host IP --port 8888 --clients 1|2|4 --mode transport|idle|movement|bombs\n"
                    +"Distributed host: --clients 1 --expected-clients 4; guest also adds --room-id UUID.\n"
                    +"One process per CSV file. Label topology with --label, source with --revision.");return;
        }
        var config=parse(args);boolean ok=true;
        if(config.mode().equals("suite")){
            for(String mode:List.of("transport","idle","movement","bombs"))for(int n:new int[]{1,2,4}){
                if(n==1&&!mode.equals("transport"))continue;
                ok=run(new Config(true,"127.0.0.1",0,n,n,null,mode,config.seconds(),config.warmup(),config.csv(),config.label(),config.revision(),config.runId()))&&ok;
            }
        }else ok=run(config);
        if(!ok)throw new IllegalStateException("Benchmark failed; inspect status/error/cleanup_ok in CSV");
    }
    private static final class Peer implements AutoCloseable{
        final ClientNetwork network=new ClientNetwork();final AtomicReference<Throwable> disconnected=new AtomicReference<>();
        final ClientWorldState world=new ClientWorldState();final LatencyMonitor rtt=new LatencyMonitor(),ack=new LatencyMonitor();
        final NavigableMap<Long,Long> pendingMoves=new TreeMap<>();final Set<Long> noticeIds=new HashSet<>();
        String session;LobbyPacketCodec.RoomView room;MatchContext match;boolean measuring,finishing;
        long tx,rx,txBytes,rxBytes,pings,moves,sequence,full,delta,snapshotBytes,stale,resync,lastResync,plantedAt,bombs,explosions;
        int pingId;Double plantAck;
        Peer(String host,int port,String name)throws Exception{network.setOnDisconnect(disconnected::set);network.connect(host,port);send(SessionPacketCodec.connectRequest(name));}
        void send(Packet p)throws Exception{network.send(p);if(measuring){tx++;txBytes+=PacketEncoder.encode(p).length;}}
        void scoped(Packet p)throws Exception{send(MatchEnvelopeCodec.wrap(match.matchId(),p));}
        void poll()throws Exception{
            for(int n=0;n<256;n++){
                Packet p=network.receive(Duration.ZERO);if(p==null)break;
                if(measuring){rx++;rxBytes+=PacketEncoder.encode(p).length;}
                switch(p.getType()){
                    case PacketType.CONNECT_ACK->session=SessionPacketCodec.decodeAcknowledgement(p).playerId();
                    case PacketType.ROOM_STATE->room=LobbyPacketCodec.decodeRoom(p);
                    case PacketType.GAME_STARTED->match=DemoPacketCodec.decodeStarted(p);
                    case PacketType.PONG->{if(measuring)rtt.recordPong(SessionPacketCodec.pingId(new Packet(PacketType.PING,p.getJsonPayload())));}
                    case PacketType.ERROR->throw new IllegalStateException("Server rejected request: "+SessionPacketCodec.errorCode(p));
                    case PacketType.GAME_OVER->{if(!finishing)throw new IllegalStateException("Match ended before measurement finished: "+p.getJsonPayload());}
                    case PacketType.GAME_SNAPSHOT,PacketType.FULL_STATE_SYNC->{
                        if(match==null)throw new IllegalStateException("Snapshot before match context");
                        var update=SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(p,match.matchId()));
                        if(measuring){if(update.isFullState())full++;else delta++;snapshotBytes+=PacketEncoder.encode(p).length;}
                        var result=world.apply(update);
                        if(result==ClientWorldState.Result.NEED_FULL_STATE){
                            long now=System.nanoTime();if(now-lastResync>TimeUnit.MILLISECONDS.toNanos(1100)){
                                scoped(ResyncPacketCodec.encode(new ResyncPacketCodec.Request(world.getTick(),ResyncPacketCodec.Reason.BASELINE_MISMATCH)));lastResync=now;if(measuring)resync++;
                            }
                        }else if(result==ClientWorldState.Result.STALE){if(measuring)stale++;}
                        else if(measuring){
                            int local=match.playerId(session).orElseThrow();var player=world.getPlayers().get(local);
                            if(player==null||!player.isAlive())throw new IllegalStateException("Benchmark player died/was removed");
                            long processed=player.getLastProcessedInput(),now=System.nanoTime();
                            var done=new ArrayList<>(pendingMoves.headMap(processed,true).keySet());
                            for(long seq:done)ack.recordSampleNanos(Math.max(0,now-pendingMoves.remove(seq)));
                            if(plantedAt>0&&plantAck==null&&world.getEntities().values().stream().anyMatch(e->e.kind()==EntityData.Kind.BOMB&&e.ownerId().equals(session)))
                                plantAck=(now-plantedAt)/1_000_000.0;
                        }
                    }
                    case PacketType.BOMB_PLANTED,PacketType.BOMB_EXPLODE->{
                        var notice=BombPacketCodec.decodeNotice(MatchEnvelopeCodec.unwrap(p,match.matchId()));
                        if(measuring&&noticeIds.add(notice.eventId())){if(p.getType()==PacketType.BOMB_PLANTED)bombs++;else explosions++;}
                    }
                    default->{}
                }
            }
            if(!network.isConnected())throw new IllegalStateException("Client disconnected",disconnected.get());
        }
        void ping()throws Exception{int id=++pingId;if(measuring){rtt.startPing(id);pings++;}send(new Packet(PacketType.PING,"{\"pingId\":"+id+"}"));}
        void begin(){measuring=true;rtt.reset();ack.reset();pendingMoves.clear();noticeIds.clear();}
        void move(String mode,long step)throws Exception{
            int id=match.playerId(session).orElseThrow();boolean top=id<=2,left=id==1||id==3;
            Direction direction;boolean moving=true;
            if(mode.equals("bombs")){
                direction=step<20?(top?Direction.DOWN:Direction.UP):(left?Direction.RIGHT:Direction.LEFT);moving=step<40;
            }else direction=((step/20)%2==0)==top?Direction.DOWN:Direction.UP;
            long seq=++sequence;if(pendingMoves.size()>=256)throw new IllegalStateException("Too many unacknowledged benchmark moves");
            pendingMoves.put(seq,System.nanoTime());moves++;scoped(MovePacketCodec.encode(new MovePacketCodec.Input(seq,direction,moving)));
        }
        public void close(){network.close();}
    }
    private static void pump(List<Peer> peers,BooleanSupplier ready,int timeoutSeconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds),nextPing=0;
        while(!ready.getAsBoolean()){
            if(System.nanoTime()>end)throw new IllegalStateException("Setup deadline: waiting for peers/ready/full snapshot");
            for(var p:peers)p.poll();
            if(System.nanoTime()>=nextPing){for(var p:peers)if(p.session!=null)p.ping();nextPing=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);}
            Thread.sleep(2);
        }
    }
    public static boolean run(Config c)throws Exception{
        var peers=new ArrayList<Peer>();LobbyGameServer server=null;Thread accept=null;var serverFailure=new AtomicReference<Throwable>();
        String error="";boolean cleanup=true;int port=c.port();double elapsed=0;String stamp=Instant.now().toString();
        try{
            if(c.local()){
                server=new LobbyGameServer(0,new BombRules(3000,1,1),new MatchRules((c.seconds()+c.warmup()+30L)*1000,.75,5,8,144,8,3,99));
                var localServer=server;accept=new Thread(()->{try{localServer.start();}catch(Throwable e){serverFailure.set(e);}},"Benchmark-Server");accept.start();port=server.port();
            }
            for(int n=0;n<c.clients();n++)peers.add(new Peer(c.host(),port,"Bench "+(n+1)));
            pump(peers,()->peers.stream().allMatch(p->p.session!=null),10);
            if(!c.mode().equals("transport")){
                var host=peers.get(0);String roomId=c.room();
                if(roomId==null){
                    host.send(LobbyPacketCodec.packet(PacketType.CREATE_ROOM,Map.of("roomName","Bench "+c.runId().substring(0,Math.min(8,c.runId().length())),"maxPlayers",c.expected())));
                    pump(peers,()->host.room!=null,10);roomId=host.room.roomId();System.out.println("ROOM_ID="+roomId);System.out.flush();
                }
                for(int n=c.room()==null?1:0;n<peers.size();n++){
                    var p=peers.get(n);p.send(LobbyPacketCodec.packet(PacketType.JOIN_ROOM,Map.of("roomId",roomId)));
                    pump(peers,()->p.room!=null,10);p.send(LobbyPacketCodec.packet(PacketType.READY,Map.of("ready",true)));
                }
                if(c.room()==null){
                    pump(peers,()->host.room.players().size()==c.expected()&&host.room.players().stream()
                            .allMatch(p->p.playerId().equals(host.session)||p.state().equals("READY")),60);
                    host.send(new Packet(PacketType.START_GAME,"{}"));
                }
                pump(peers,()->peers.stream().allMatch(p->p.match!=null&&!p.world.needsFullState()),60);
                if(peers.stream().anyMatch(p->p.match.players().size()!=c.expected()))throw new IllegalStateException("Unexpected match roster size");
            }
            long warmEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(c.warmup()),nextPing=0;
            while(System.nanoTime()<warmEnd){for(var p:peers)p.poll();if(System.nanoTime()>=nextPing){for(var p:peers)p.ping();nextPing=System.nanoTime()+200_000_000L;}Thread.sleep(1);}
            for(var p:peers){p.poll();p.begin();}
            long start=System.nanoTime(),end=start+TimeUnit.SECONDS.toNanos(c.seconds()),nextMove=start;nextPing=start;long step=0;
            if(c.mode().equals("bombs"))for(var p:peers){p.plantedAt=System.nanoTime();p.scoped(BombPacketCodec.plant(1));}
            while(System.nanoTime()<end){
                for(var p:peers)p.poll();long now=System.nanoTime();
                if(now>=nextPing){for(var p:peers)p.ping();nextPing=now+200_000_000L;}
                if((c.mode().equals("movement")||c.mode().equals("bombs"))&&now>=nextMove){for(var p:peers)p.move(c.mode(),step);step++;nextMove=now+50_000_000L;}
                Thread.sleep(1);
            }
            elapsed=(System.nanoTime()-start)/1_000_000_000.0;
            for(var p:peers){
                if(p.rtt.getSampleCount()==0||p.world.needsFullState()&&p.match!=null)throw new IllegalStateException("Incomplete measurement/baseline");
                if((c.mode().equals("movement")||c.mode().equals("bombs"))&&p.ack.getSampleCount()==0)
                    throw new IllegalStateException("No movement acknowledgement observed");
                if(c.mode().equals("bombs")&&(p.bombs!=c.expected()||p.explosions!=c.expected()||p.plantAck==null))
                    throw new IllegalStateException("Missing shared bomb/explosion or plant acknowledgement");
            }
            for(var p:peers){p.measuring=false;p.finishing=true;}
            // Separate runners may start a few ms apart. Do not abort another runner at our window boundary.
            if(!c.local()&&c.clients()<c.expected()){
                long settle=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
                while(System.nanoTime()<settle){for(var p:peers)p.poll();Thread.sleep(5);}
            }
        }catch(Exception e){error=e.getClass().getSimpleName()+": "+e.getMessage();}
        finally{
            for(var p:peers){p.measuring=false;p.close();cleanup&=!p.network.isReaderAlive()&&p.network.listenerCount()==0;}
            if(server!=null){server.close();if(accept!=null)accept.join(3000);cleanup&=server.transport().isTerminated()&&server.activeMatchCount()==0&&server.snapshotWorkerCount()==0&&!accept.isAlive();
                if(serverFailure.get()!=null||server.failure().isPresent())error+=" Local server failure";}
        }
        boolean ok=error.isEmpty()&&cleanup;var rows=new ArrayList<Map<String,String>>();
        int count=Math.max(1,peers.size());for(int i=0;i<count;i++){
            var row=new LinkedHashMap<String,String>();
            put(row,"run_id",c.runId(),"scenario",c.mode()+"-"+c.expected(),"timestamp_utc",stamp,"label",c.label(),"revision",c.revision(),
                    "scope",c.local()?"single_jvm_loopback":"remote_runner","host",c.host(),"port",port,"runner_clients",c.clients(),"match_players",c.mode().equals("transport")?0:c.expected(),
                    "client_index",i+1,"mode",c.mode(),"warmup_seconds",c.warmup(),"window_seconds",decimal(elapsed),"probe_interval_ms",200,
                    "status",ok?"PASS":"FAIL","error",error,"cleanup_ok",cleanup,"java_version",System.getProperty("java.version"),
                    "os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"),"processors",Runtime.getRuntime().availableProcessors());
            if(!peers.isEmpty()){
                var p=peers.get(i);put(row,"tx_packets",p.tx,"rx_packets",p.rx,"tx_bytes",p.txBytes,"rx_bytes",p.rxBytes,
                        "tx_bytes_per_second",elapsed>0?decimal(p.txBytes/elapsed):"","rx_bytes_per_second",elapsed>0?decimal(p.rxBytes/elapsed):"",
                        "ping_sent",p.pings,"rtt_samples",p.rtt.getSampleCount(),"rtt_mean_ms",stat(p.rtt,p.rtt.getAverageRTT()),"rtt_min_ms",stat(p.rtt,p.rtt.getMinRTT()),
                        "rtt_p95_ms",stat(p.rtt,p.rtt.getP95RTT()),"rtt_max_ms",stat(p.rtt,p.rtt.getMaxRTT()),"pending_ping_end",p.pings-p.rtt.getSampleCount(),
                        "moves_sent",p.moves,"move_ack_samples",p.ack.getSampleCount(),"move_ack_mean_ms",stat(p.ack,p.ack.getAverageRTT()),"move_ack_p95_ms",stat(p.ack,p.ack.getP95RTT()),
                        "pending_move_end",p.pendingMoves.size(),"full_snapshots",p.full,"delta_snapshots",p.delta,"snapshot_bytes",p.snapshotBytes,"stale_snapshots",p.stale,
                        "resync_requests",p.resync,"bombs_observed",p.bombs,"explosions_observed",p.explosions,"plant_ack_ms",p.plantAck==null?"":decimal(p.plantAck));
            }
            rows.add(row);
        }
        BenchmarkCsv.append(c.csv(),COLUMNS,rows);System.out.println((ok?"PASS ":"FAIL ")+c.mode()+" clients="+c.clients()+" expected="+c.expected()+" "+error);return ok;
    }
    private static String decimal(double v){return String.format(Locale.ROOT,"%.3f",v);}
    private static String stat(LatencyMonitor m,double value){return m.getSampleCount()==0?"":decimal(value);}
    private static void put(Map<String,String> map,Object... values){for(int i=0;i<values.length;i+=2)map.put(values[i].toString(),String.valueOf(values[i+1]));}
}

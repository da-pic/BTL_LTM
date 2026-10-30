package com.bombit.server.game.mode.paradox;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.GameEventSink;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.function.*;

/** Điều phối Paradox trên game thread; không tạo scheduler hoặc sửa session/token. */
public final class ParadoxGameplayExtension implements GameplayExtension {
    public enum Outcome { ACCEPTED, NOT_RUNNING, UNKNOWN_PLAYER, NOT_ELIGIBLE, ALREADY_USED,
        REWIND_BUSY, COOLDOWN, NOT_ENOUGH_HISTORY, STALE_TIMELINE, STALE_REQUEST, REQUEST_CONFLICT }
    public record RewindResult(int playerId, long requestId, long requestedEpoch, Outcome outcome,
            long serverTick, long simulationTick, long timelineEpoch) { }
    /** Ảnh bất biến cho runtime/consumer, không trả world hoặc history mutable. */
    public record Status(long serverTick, long simulationTick, long timelineEpoch, int historySize,
            Set<Integer> availableCharges, long cooldownRemainingMillis, List<RewindResult> results) {
        public Status { availableCharges=Set.copyOf(availableCharges);results=List.copyOf(results); }
    }
    private static final int CACHE_SIZE=64;
    private final GameState state;
    private final Map<String,Integer> players;
    private final MatchGameplayExtension gameplay;
    private final MatchRules rules;
    private final LongSupplier nanos;
    private final Set<Integer> frozen=new HashSet<>();
    private final Map<Integer,Long> rewindHighWater=new HashMap<>(),plantHighWater=new HashMap<>();
    private final Map<Integer,LinkedHashMap<Long,RewindResult>> rewindCache=new HashMap<>();
    private final Map<Integer,LinkedHashMap<Long,PlantRequestResult>> plantCache=new HashMap<>();
    private final ArrayDeque<RewindResult> results=new ArrayDeque<>();
    private ParadoxTimeline<MatchGameplayExtension.Checkpoint> timeline;
    private Thread owner;
    private boolean restored,finished,stopped,hasRewound;
    private long started,lastRewindNanos;
    private volatile Status status;
    public ParadoxGameplayExtension useTeams(List<Integer> slots){
        if(owner!=null)throw new IllegalStateException("Paradox already started");
        gameplay.useTeams(slots);return this;
    }

    public ParadoxGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombs,MatchRules rules,long seed) {
        this(state,players,bombs,rules,seed,event->{},result->{},System::nanoTime,System::currentTimeMillis);
    }
    public ParadoxGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombs,MatchRules rules,long seed,
            GameEventSink events,Consumer<BombCommandResult> bombResults,LongSupplier nanos,LongSupplier epoch) {
        this.state=Objects.requireNonNull(state);this.players=Map.copyOf(players);this.rules=Objects.requireNonNull(rules);
        if(players.size()<2||players.size()>4||new HashSet<>(players.values()).size()!=players.size())
            throw new IllegalArgumentException("Paradox requires 2-4 unique players");
        this.nanos=Objects.requireNonNull(nanos);
        gameplay=new MatchGameplayExtension(state,players,bombs,rules,new ParadoxItemSpawnPolicy(rules.dropChance(),seed),
                events,bombResults,nanos,epoch);
    }
    private void check() { if(owner!=Thread.currentThread()||stopped)throw new IllegalStateException("Paradox outside active game thread"); }
    public void onStart() {
        if(owner!=null)throw new IllegalStateException("Paradox starts once");
        owner=Thread.currentThread();started=nanos.getAsLong();
        timeline=new ParadoxTimeline<>(players.values());gameplay.onStart();
        timeline.capture(gameplay.captureCheckpoint());publish();
    }
    public void beforeTick() { check();restored=false;if(!finished)timeline.beginStep(); }
    public long timelineEpoch() { check();return timeline.timelineEpoch(); }
    public boolean skipSimulationTick() { check();return restored; }
    public Status status() { return status; }

    public void processCommand(GameCommand command) {
        check();int id=command.getPlayerId();
        if(command instanceof PlayerDisconnectedCommand)frozen.add(id);
        if(command instanceof PlayerReconnectedCommand)frozen.remove(id);
        if(command instanceof RewindCommand rewind) { rewind(rewind);return; }
        if(command instanceof PlantBombCommand plant) { plant(plant);return; }
        gameplay.processCommand(command);
    }
    private void rewind(RewindCommand command) {
        int id=command.getPlayerId();long request=command.requestId();
        if(!players.containsValue(id)){report(result(command,Outcome.UNKNOWN_PLAYER));return;}
        var cache=rewindCache.computeIfAbsent(id,k->new LinkedHashMap<>());
        var previous=cache.get(request);
        if(previous!=null) {
            report(previous.requestedEpoch()==command.timelineEpoch()?previous:result(command,Outcome.REQUEST_CONFLICT));return;
        }
        if(request<=rewindHighWater.getOrDefault(id,0L)) {report(result(command,Outcome.STALE_REQUEST));return;}
        // Counter tiến cả với request bị từ chối; retry không được biến thành thao tác mới.
        rewindHighWater.put(id,request);
        Outcome outcome;
        if(finished||state.getMatchState()!=GameState.MatchState.RUNNING
                ||nanos.getAsLong()-started>=rules.durationMillis()*1_000_000L)outcome=Outcome.NOT_RUNNING;
        else if(restored)outcome=Outcome.REWIND_BUSY;
        else if(command.timelineEpoch()!=timeline.timelineEpoch())outcome=Outcome.STALE_TIMELINE;
        else {
            var player=state.getPlayer(id);
            var answer=timeline.rewind(id,true,player!=null&&player.isAlive()&&!frozen.contains(id),nanos.getAsLong(),gameplay::restoreCheckpoint);
            outcome=Outcome.valueOf(answer.name());
            if(outcome==Outcome.ACCEPTED) {
                restored=true;hasRewound=true;lastRewindNanos=nanos.getAsLong();
                // Không tiếp tục intent giữ phím từ nhánh cũ, kể cả input kiểu legacy.
                for(var p:state.getPlayers())p.setMoving(false);
            }
        }
        var answer=result(command,outcome);remember(cache,request,answer);report(answer);publish();
    }
    private RewindResult result(RewindCommand c,Outcome outcome) {
        return new RewindResult(c.getPlayerId(),c.requestId(),c.timelineEpoch(),outcome,
                timeline.serverTick(),timeline.simulationTick(),timeline.timelineEpoch());
    }
    private void report(RewindResult result) {
        if(results.size()==128)results.removeFirst();results.addLast(result);
    }
    private static <T> void remember(LinkedHashMap<Long,T> cache,long id,T result) {
        cache.put(id,result);while(cache.size()>CACHE_SIZE)cache.remove(cache.keySet().iterator().next());
    }
    private void plant(PlantBombCommand command) {
        long request=command.requestId();int id=command.getPlayerId();
        if(request<=0)return; // Paradox không chấp nhận thao tác không có khóa idempotency.
        if(!players.containsValue(id)) {
            state.recordPlantResult(new PlantRequestResult(id,request,command.bombType(),false,"PLAYER_NOT_FOUND",null));return;
        }
        var cache=plantCache.computeIfAbsent(id,k->new LinkedHashMap<>());
        var previous=cache.get(request);
        if(previous!=null) {
            state.recordPlantResult(previous.bombType()==command.bombType()?previous:
                    new PlantRequestResult(id,request,command.bombType(),false,"REQUEST_CONFLICT",null));return;
        }
        if(request<=plantHighWater.getOrDefault(id,0L)) {
            state.recordPlantResult(new PlantRequestResult(id,request,command.bombType(),false,"STALE_REQUEST",null));return;
        }
        plantHighWater.put(id,request);
        if(command.bombType()!=BombType.NORMAL)state.recordPlantResult(new PlantRequestResult(id,request,command.bombType(),false,"UNSUPPORTED_BOMB_TYPE",null));
        else if(restored)state.recordPlantResult(new PlantRequestResult(id,request,command.bombType(),false,"STALE_TIMELINE",null));
        else gameplay.processCommand(command);
        remember(cache,request,state.plantResults().getLast());
    }
    public void update(long nowMillis) {
        check();
        if(!finished) {
            if(!restored) {
                gameplay.update(nowMillis);timeline.completeSimulationTick();
            }
            if(state.getMatchState()==GameState.MatchState.FINISHED) {finished=true;timeline.finish();}
            else timeline.capture(gameplay.captureCheckpoint());
        }
        publish();
    }
    private void publish() {
        var charges=new HashSet<Integer>();
        if(!finished)for(int id:players.values())if(state.getPlayer(id)!=null&&timeline.hasCharge(id))charges.add(id);
        long remaining=hasRewound?Math.max(0,ParadoxTimeline.COOLDOWN_NANOS-(nanos.getAsLong()-lastRewindNanos)):0;
        status=new Status(timeline.serverTick(),timeline.simulationTick(),timeline.timelineEpoch(),timeline.historySize(),
                charges,(remaining+999_999L)/1_000_000L,List.copyOf(results));
    }
    public void onStop() {
        if(owner==null)return;check();
        try {timeline.finish();gameplay.onStop();}
        finally {finished=true;stopped=true;results.clear();rewindCache.clear();plantCache.clear();
            rewindHighWater.clear();plantHighWater.clear();frozen.clear();publish();}
    }
}

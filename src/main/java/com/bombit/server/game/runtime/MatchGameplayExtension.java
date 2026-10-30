package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.TilePosition;
import java.util.*;
import java.util.function.*;

/** One tick: bomb batch/damage -> destroy exposed items -> block drops -> pickups -> final decision. */
public final class MatchGameplayExtension implements GameplayExtension,
        com.bombit.server.game.checkpoint.CheckpointParticipant<MatchGameplayExtension.Checkpoint> {
    /** Shadow dùng chung bom/item nhưng tự quyết định kết quả đội sau bước gameplay nền. */
    public enum ResultPolicy { INDIVIDUAL, EXTERNAL }
    private final ResultPolicy resultPolicy;
    private final GameState state;
    private final Map<String,Integer> players;
    private final MatchRules rules;
    private final BombGameplayExtension bombs;
    private final ItemWorldAdapter world;
    private final ItemSystem items;
    private final CheckpointItemSpawnPolicy checkpointDrops;
    private final boolean checkpointSupported;
    private final UUID checkpointOwner = UUID.randomUUID();
    private boolean stopped;
    private final LongSupplier nanos;
    private final Set<TilePosition> blocks=new LinkedHashSet<>(),blast=new HashSet<>();
    private final Map<Integer,Long> deaths=new HashMap<>();
    private Thread owner;
    private long started,tick;
    private MatchResult result;
    private TeamMatchPolicy teamPolicy;
    private TeamMatchResult teamResult;
    /** Composition root chọn trước start; roster không nằm trong checkpoint để rewind không đổi đội. */
    public MatchGameplayExtension useTeams(List<Integer> slots){
        if(owner!=null||resultPolicy!=ResultPolicy.INDIVIDUAL)throw new IllegalStateException("Cannot change result policy");
        var policy=new TeamMatchPolicy(slots);
        if(!policy.teams().keySet().equals(new HashSet<>(players.values())))throw new IllegalArgumentException("Team roster mismatch");
        teamPolicy=policy;return this;
    }
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results) {
        this(state,players,bombRules,rules,drops,events,results,System::nanoTime,System::currentTimeMillis);
    }
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results,
                                  LongSupplier nanos,LongSupplier epoch) {
        this(state,players,bombRules,rules,drops,events,results,nanos,epoch,ResultPolicy.INDIVIDUAL);
    }
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results,
                                  LongSupplier nanos,LongSupplier epoch,ResultPolicy resultPolicy) {
        this(state,players,bombRules,rules,drops,events,results,nanos,epoch,resultPolicy,BombExtras.NONE,null);
    }
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results,
                                  LongSupplier nanos,LongSupplier epoch,ResultPolicy resultPolicy,BombExtras extras,IntSupplier ids) {
        this.resultPolicy=Objects.requireNonNull(resultPolicy);
        this.state=Objects.requireNonNull(state);this.players=Map.copyOf(players);this.rules=Objects.requireNonNull(rules);
        this.nanos=Objects.requireNonNull(nanos);
        checkpointDrops = drops instanceof CheckpointItemSpawnPolicy policy ? policy : null;
        // Extras của Shadow có state riêng: không cho xuất checkpoint thiếu scan/trạm.
        checkpointSupported = checkpointDrops != null && extras == BombExtras.NONE && ids == null;
        if(rules.maxBombs()<bombRules.quota()||rules.maxRange()<bombRules.range()
                ||state.getPlayers().stream().anyMatch(p->p.getSpeed()>rules.maxSpeed()))throw new IllegalArgumentException("Caps below initial stats");
        world=new ItemWorldAdapter(state,players,rules);
        items=new ItemSystem(drops,e->{state.recordGameEvent(e);events.publish(e);});
        bombs=new BombGameplayExtension(state,players,bombRules,e->{
            if(e instanceof BlockDestroyedEvent b)blocks.add(b.position());
            if(e instanceof BombExplodedEvent b)blast.addAll(b.affectedCells());
            events.publish(e);
        },results,nanos,epoch,extras,ids);
        publish((int)((rules.durationMillis()+999)/1000));
    }
    public void onStart(){owner=Thread.currentThread();started=nanos.getAsLong();world.bindToGameThread();
        for(var p:state.getPlayers()){p.setLives(rules.initialLives());p.setShield(false);}
        bombs.onStart();publish((int)((rules.durationMillis()+999)/1000));}
    private void check(){if(owner!=Thread.currentThread() || stopped)throw new IllegalStateException("Match outside game thread or stopped");}
    public void processCommand(GameCommand c){check();bombs.processCommand(c);}
    public void update(long now){
        check();tick++;if(result!=null||teamResult!=null)return;
        bombs.update(now);
        for(var cell:blast)world.itemAt(cell).ifPresent(i->world.removeItem(i.id()));
        blast.clear();
        for(var cell:blocks)items.trySpawn(cell,now,world);blocks.clear();
        // Stable numeric player order is the tie-breaker, independent of packet/thread scheduling.
        players.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(p->items.tryPickUp(p.getKey(),now,world));
        for(int id:players.values()) {var p=state.getPlayer(id);if(p==null||!p.isAlive())deaths.putIfAbsent(id,tick);}
        int alive=players.size()-deaths.size();long elapsed=nanos.getAsLong()-started;
        int remaining=(int)Math.max(0,(rules.durationMillis()-elapsed/1_000_000L+999)/1000);
        MatchResult.Reason reason=alive==0?MatchResult.Reason.ALL_DEAD:alive==1?MatchResult.Reason.LAST_ALIVE:
                elapsed>=rules.durationMillis()*1_000_000L?MatchResult.Reason.TIMEOUT:null;
        if(teamPolicy!=null){
            teamResult=teamPolicy.decide(state,tick,elapsed>=rules.durationMillis()*1_000_000L);
            if(teamResult!=null)state.finishMatch();
        }else if(reason!=null&&resultPolicy==ResultPolicy.INDIVIDUAL){
            Integer winner=reason==MatchResult.Reason.LAST_ALIVE?players.values().stream().filter(id->!deaths.containsKey(id)).findFirst().orElseThrow():null;
            var ranks=players.values().stream().sorted().map(id->{
                int rank=deaths.containsKey(id)?1+alive+(int)deaths.values().stream().filter(t->t>deaths.get(id)).count():1;
                return new MatchResult.Ranking(id,rank);
            }).sorted(Comparator.comparingInt(MatchResult.Ranking::rank).thenComparingInt(MatchResult.Ranking::playerId)).toList();
            result=new MatchResult(tick,winner,reason,ranks);state.finishMatch();
        }
        publish(remaining);
    }
    private void publish(int remaining){
        var stats=state.getPlayers().stream().sorted(Comparator.comparingInt(Player::getId))
                .map(p->new MatchFrame.Stats(p.getId(),p.getBombLimit(),p.getBombRange(),p.getLives(),p.hasShield(),p.getRespawns())).toList();
        state.setMatchFrame(new MatchFrame(remaining,stats,result,teamResult));
    }
    public void onStop(){check();try{bombs.onStop();}finally{world.clear();blocks.clear();blast.clear();stopped=true;}}

    /** Checkpoint version 1 đầy đủ cho nền Classic của Paradox, chỉ chụp tại ranh giới tick. */
    public record Checkpoint(int version, UUID ownerId,
            com.bombit.server.game.checkpoint.CoreWorldCheckpoint core,
            BombGameplayExtension.Checkpoint bombs, List<ItemWorldAdapter.SavedItem> items,
            Map<Integer,Long> deaths, long simulationTick, long randomState) {
        public Checkpoint {
            Objects.requireNonNull(ownerId); Objects.requireNonNull(core); Objects.requireNonNull(bombs);
            items = List.copyOf(items); deaths = Map.copyOf(deaths);
            if (version != 1 || simulationTick < 0 || deaths.entrySet().stream()
                    .anyMatch(e -> e.getKey() <= 0 || e.getValue() < 0 || e.getValue() > simulationTick))
                throw new IllegalArgumentException("Invalid gameplay checkpoint");
        }
    }
    private void checkCheckpointAccess() {
        check();
        if (!checkpointSupported) throw new IllegalStateException("Requires checkpoint RNG and no external gameplay extras/allocator");
        if (result != null || teamResult != null || state.getMatchState() != GameState.MatchState.RUNNING)
            throw new IllegalStateException("Cannot rewind a finished match");
        if (!blocks.isEmpty() || !blast.isEmpty()) throw new IllegalStateException("Checkpoint requires completed gameplay tick");
    }
    public Checkpoint captureCheckpoint() {
        checkCheckpointAccess();
        return new Checkpoint(1, checkpointOwner, com.bombit.server.game.checkpoint.CoreWorldCheckpoint.capture(state),
                bombs.captureCheckpoint(), world.captureCheckpoint(), deaths, tick, checkpointDrops.captureCheckpoint());
    }
    /** Toàn bộ validation chạy trước lần ghi đầu tiên để checkpoint hỏng không làm mất world. */
    public void validateRestore(Checkpoint checkpoint) {
        checkCheckpointAccess(); Objects.requireNonNull(checkpoint);
        if (!checkpointOwner.equals(checkpoint.ownerId())) throw new IllegalArgumentException("Checkpoint from another match");
        checkpoint.core().validateRestore(state); bombs.validateRestore(checkpoint.bombs());
        world.validateRestore(checkpoint.items()); checkpointDrops.validateRestore(checkpoint.randomState());
        var core = checkpoint.core();
        for (var b : checkpoint.bombs().bombs()) validateCheckpointCell(core, b.position());
        for (var i : checkpoint.items()) validateCheckpointCell(core, i.position());
        if (!players.values().containsAll(checkpoint.deaths().keySet())) throw new IllegalArgumentException("Unknown death identity");
        var originalIds = new HashSet<>(players.values());
        if (!originalIds.containsAll(core.players().stream().map(Player.SimulationCheckpoint::playerId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Wrong checkpoint roster");
    }
    private void validateCheckpointCell(com.bombit.server.game.checkpoint.CoreWorldCheckpoint core, TilePosition cell) {
        if (cell.x() < 0 || cell.y() < 0 || cell.x() >= core.width() || cell.y() >= core.height()
                || core.tiles().get(cell.y() * core.width() + cell.x()) != com.bombit.server.game.model.TileType.EMPTY)
            throw new IllegalArgumentException("Checkpoint entity on blocked tile");
    }
    public void restoreCheckpoint(Checkpoint checkpoint) {
        validateRestore(checkpoint);
        checkpoint.core().restore(state);
        bombs.restoreCheckpoint(checkpoint.bombs()); world.restoreCheckpoint(checkpoint.items());
        checkpointDrops.restoreCheckpoint(checkpoint.randomState());
        deaths.clear(); deaths.putAll(checkpoint.deaths()); tick = checkpoint.simulationTick();
        // Người hết grace không quay lại. Frozen được BombGameplayExtension giữ theo lifecycle hiện tại.
        for (int id : players.values()) if (state.getPlayer(id) == null) deaths.putIfAbsent(id, tick);
        blocks.clear(); blast.clear(); state.clearGameNotices(); state.clearPlantResults();
        // started/deadline, event ID, item/bomb allocator và input ACK không bị rewind.
        int remaining = (int)Math.max(0, (rules.durationMillis() - (nanos.getAsLong() - started) / 1_000_000L + 999) / 1000);
        publish(remaining);
    }
}

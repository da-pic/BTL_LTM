package com.bombit.server.game.bomb;

import com.bombit.server.game.core.*;
import com.bombit.server.game.event.GameEventSink;
import com.bombit.server.game.event.BombExplodedEvent;
import com.bombit.common.dto.BombFrame;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.function.*;

/** Command -> placement, movement -> due explosions, then the loop publishes snapshots. No scheduler. */
public final class BombGameplayExtension implements GameplayExtension {
    private final GameState state;
    private final BombWorldAdapter world;
    private final BombSystem system;
    private final Map<Integer,String> sessions=new HashMap<>();
    private final Set<Integer> disconnected=new HashSet<>();
    private final LongSupplier nanoClock,epochClock;
    private final Consumer<BombCommandResult> results;
    private Thread owner;
    private boolean stopped;
    private final BombRules rules;
    private final Map<Integer,BombFrame.Explosion> explosions=new LinkedHashMap<>();
    private final Map<Integer,Long> explosionNanos=new HashMap<>();

    public BombGameplayExtension(GameState state,Map<String,Integer> players,BombRules rules,
                                 GameEventSink events,Consumer<BombCommandResult> results) {
        this(state,players,rules,events,results,System::nanoTime,System::currentTimeMillis);
    }
    /** Injectable clocks allow deadline tests without sleep or changing the simulation period. */
    public BombGameplayExtension(GameState state,Map<String,Integer> players,BombRules rules,
                                 GameEventSink events,Consumer<BombCommandResult> results,
                                 LongSupplier nanoClock,LongSupplier epochClock) {
        this(state,players,rules,events,results,nanoClock,epochClock,BombExtras.NONE,null);
    }
    public BombGameplayExtension(GameState state,Map<String,Integer> players,BombRules rules,
                                 GameEventSink events,Consumer<BombCommandResult> results,
                                 LongSupplier nanoClock,LongSupplier epochClock,BombExtras extras,IntSupplier ids) {
        this.state=Objects.requireNonNull(state);this.nanoClock=Objects.requireNonNull(nanoClock);
        this.rules=Objects.requireNonNull(rules);
        this.epochClock=Objects.requireNonNull(epochClock);this.results=Objects.requireNonNull(results);
        players.forEach((session,id)->{if(sessions.putIfAbsent(id,session)!=null)throw new IllegalArgumentException("Duplicate player mapping");});
        world=new BombWorldAdapter(state,players,rules,nanoClock,extras);
        GameEventSink sink=event->{
            if(event instanceof com.bombit.server.game.event.BombPlacedEvent || event instanceof BombExplodedEvent
                    || event instanceof com.bombit.server.game.event.PlayerDiedEvent) state.recordGameEvent(event);
            if(event instanceof BombExplodedEvent e) {
                explosions.put(e.bombId(),new BombFrame.Explosion(e.bombId(),e.occurredAtMillis(),
                        e.occurredAtMillis()+BombFrame.ANIMATION_MILLIS,e.affectedCells()));
                explosionNanos.put(e.bombId(),nanoClock.getAsLong());
            }
            events.publish(event);
        };
        system=ids==null?new BombSystem(rules.fuseMillis(),sink):new BombSystem(rules.fuseMillis(),sink,ids);
    }
    @Override public void onStart() {
        if(owner!=null) throw new IllegalStateException("Extension starts once");
        owner=Thread.currentThread();world.bindToGameThread();
        for(Player p:state.getPlayers()){p.setBombLimit(rules.quota());p.setBombRange(rules.range());}
        publishEffects(epochClock.getAsLong());
    }
    private void checkThread() {
        if(owner!=Thread.currentThread()||stopped) throw new IllegalStateException("Extension not active on its game thread");
    }
    @Override public void processCommand(GameCommand command) {
        checkThread();int id=command.getPlayerId();
        if(command instanceof PlayerDisconnectedCommand) disconnected.add(id);
        if(command instanceof PlayerReconnectedCommand) disconnected.remove(id);
        if(!(command instanceof PlantBombCommand plant)) return;
        BombPlacementResult result;
        if(state.getMatchState()!=GameState.MatchState.RUNNING) result=BombPlacementResult.failure(BombError.MATCH_NOT_RUNNING);
        else if(disconnected.contains(id)) result=BombPlacementResult.failure(BombError.PLAYER_DISCONNECTED);
        else {
            String session=sessions.get(id);
            result=session==null?BombPlacementResult.failure(BombError.PLAYER_NOT_FOUND):system.plantBomb(session,epochClock.getAsLong(),world);
        }
        if(plant.requestId()>0)state.recordPlantResult(new com.bombit.common.dto.PlantRequestResult(id,plant.requestId(),plant.bombType(),result.succeeded(),
                result.succeeded()?null:result.error().name(),result.succeeded()?result.bomb().id():null));
        results.accept(new BombCommandResult(id,result.error(),result.succeeded()?result.bomb().id():null));
    }
    @Override public void update(long nowMillis) {
        checkThread();if(state.getMatchState()!=GameState.MatchState.RUNNING) return;
        system.explodeBombs(world.dueBombIds(nanoClock.getAsLong()),nowMillis,world);
        long nanos=nanoClock.getAsLong();
        explosions.keySet().removeIf(id->{
            if(nanos-explosionNanos.get(id)<BombFrame.ANIMATION_MILLIS*1_000_000L) return false;
            explosionNanos.remove(id);return true;
        });
        publishEffects(nowMillis);
    }
    private void publishEffects(long now) { state.setBombFrame(new BombFrame(now,List.copyOf(explosions.values()))); }

    public record SavedExplosion(int bombId, long ageNanos, Set<com.bombit.server.game.model.TilePosition> cells) {
        public SavedExplosion {
            cells = Set.copyOf(cells);
            if (bombId <= 0 || ageNanos < 0 || ageNanos >= BombFrame.ANIMATION_MILLIS * 1_000_000L
                    || cells.isEmpty() || cells.size() > 1024) throw new IllegalArgumentException("Invalid saved explosion");
        }
    }
    public record Checkpoint(List<BombWorldAdapter.SavedBomb> bombs, List<SavedExplosion> explosions) {
        public Checkpoint { bombs = List.copyOf(bombs); explosions = List.copyOf(explosions); }
    }
    /** Capture giữa các tick, giữ thời gian còn lại của fuse và hiệu ứng; không giữ disconnect. */
    public Checkpoint captureCheckpoint() {
        checkThread(); long now = nanoClock.getAsLong();
        var effects = new ArrayList<SavedExplosion>();
        for (var effect : explosions.values()) {
            long age = now - explosionNanos.get(effect.bombId());
            if (age < BombFrame.ANIMATION_MILLIS * 1_000_000L)
                effects.add(new SavedExplosion(effect.bombId(), age, effect.cells()));
        }
        return new Checkpoint(world.captureCheckpoint(), effects);
    }
    public void validateRestore(Checkpoint checkpoint) {
        checkThread(); Objects.requireNonNull(checkpoint); world.validateRestore(checkpoint.bombs());
        var ids = new HashSet<Integer>();
        for (var effect : checkpoint.explosions()) {
            if (!ids.add(effect.bombId()) || checkpoint.bombs().stream().anyMatch(b -> b.id() == effect.bombId())
                    || effect.cells().stream().anyMatch(p -> !state.getMap().isInsideMap(p.x(), p.y())))
                throw new IllegalArgumentException("Invalid explosion checkpoint");
        }
    }
    public void restoreCheckpoint(Checkpoint checkpoint) {
        validateRestore(checkpoint);
        long now = epochClock.getAsLong(), nanos = nanoClock.getAsLong();
        world.restoreCheckpoint(checkpoint.bombs(), now);
        explosions.clear(); explosionNanos.clear();
        for (var effect : checkpoint.explosions()) {
            long start = now - effect.ageNanos() / 1_000_000L;
            explosions.put(effect.bombId(), new BombFrame.Explosion(effect.bombId(), start,
                    start + BombFrame.ANIMATION_MILLIS, effect.cells()));
            explosionNanos.put(effect.bombId(), nanos - effect.ageNanos());
        }
        // Bộ đếm ID và disconnected giữ hiện tại; restore không phát lại event gây damage.
        for (int id : disconnected) { var player = state.getPlayer(id); if (player != null) player.setMoving(false); }
        publishEffects(now);
    }
    @Override public void onStop() {
        if(owner==null) return;checkThread();world.clear();disconnected.clear();
        explosions.clear();explosionNanos.clear();publishEffects(epochClock.getAsLong());stopped=true;
        state.clearGameNotices();
        state.clearPlantResults();
    }
}

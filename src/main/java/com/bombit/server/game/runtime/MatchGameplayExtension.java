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
public final class MatchGameplayExtension implements GameplayExtension {
    private final GameState state;
    private final Map<String,Integer> players;
    private final MatchRules rules;
    private final BombGameplayExtension bombs;
    private final ItemWorldAdapter world;
    private final ItemSystem items;
    private final LongSupplier nanos;
    private final Set<TilePosition> blocks=new LinkedHashSet<>(),blast=new HashSet<>();
    private final Map<Integer,Long> deaths=new HashMap<>();
    private Thread owner;
    private long started,tick;
    private MatchResult result;
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results) {
        this(state,players,bombRules,rules,drops,events,results,System::nanoTime,System::currentTimeMillis);
    }
    public MatchGameplayExtension(GameState state,Map<String,Integer> players,BombRules bombRules,MatchRules rules,
                                  ItemSpawnPolicy drops,GameEventSink events,Consumer<BombCommandResult> results,
                                  LongSupplier nanos,LongSupplier epoch) {
        this.state=Objects.requireNonNull(state);this.players=Map.copyOf(players);this.rules=Objects.requireNonNull(rules);
        this.nanos=Objects.requireNonNull(nanos);
        if(rules.maxBombs()<bombRules.quota()||rules.maxRange()<bombRules.range()
                ||state.getPlayers().stream().anyMatch(p->p.getSpeed()>rules.maxSpeed()))throw new IllegalArgumentException("Caps below initial stats");
        world=new ItemWorldAdapter(state,players,rules);
        items=new ItemSystem(drops,e->{state.recordGameEvent(e);events.publish(e);});
        bombs=new BombGameplayExtension(state,players,bombRules,e->{
            if(e instanceof BlockDestroyedEvent b)blocks.add(b.position());
            if(e instanceof BombExplodedEvent b)blast.addAll(b.affectedCells());
            events.publish(e);
        },results,nanos,epoch);
        publish((int)((rules.durationMillis()+999)/1000));
    }
    public void onStart(){owner=Thread.currentThread();started=nanos.getAsLong();world.bindToGameThread();
        for(var p:state.getPlayers()){p.setLives(rules.initialLives());p.setShield(false);}
        bombs.onStart();publish((int)((rules.durationMillis()+999)/1000));}
    private void check(){if(owner!=Thread.currentThread())throw new IllegalStateException("Match outside game thread");}
    public void processCommand(GameCommand c){check();bombs.processCommand(c);}
    public void update(long now){
        check();tick++;if(result!=null)return;
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
        if(reason!=null){
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
        state.setMatchFrame(new MatchFrame(remaining,stats,result));
    }
    public void onStop(){check();try{bombs.onStop();}finally{world.clear();blocks.clear();blast.clear();}}
}

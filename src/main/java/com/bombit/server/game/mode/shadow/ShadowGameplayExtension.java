package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.GameEventSink;
import com.bombit.server.game.item.*;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.function.*;

/**
 * Game thread: bom/damage/drop/pickup/hồi sinh → loại đội → capture/điểm đồng thời → timeout.
 * Tái sử dụng gameplay nền, không chạy luật LAST_ALIVE của Classic cho trận hai đội.
 */
public final class ShadowGameplayExtension implements GameplayExtension {
    private final GameState state;
    private final ShadowRoster roster;
    private final EffectiveRules.ShadowRelay rules;
    private final MatchGameplayExtension gameplay;
    private final LongSupplier nanos;
    private final Set<Integer> frozen = new HashSet<>();
    private final List<Objective> objectives = new ArrayList<>();
    private Thread owner;
    private boolean stopped;
    private long started, tick;
    private int scoreA, scoreB;
    private ShadowFrame.Result result;
    private final ShadowScans scans;
    private record Cached(BombType type,PlantRequestResult result) { }
    private final Map<Integer,LinkedHashMap<Long,Cached>> requests=new HashMap<>();
    private final Map<Integer,Long> highWater=new HashMap<>(),rateStarted=new HashMap<>();
    private final Map<Integer,Integer> rateCount=new HashMap<>();

    private static final class Objective {
        final int id, x, y;
        TeamId owner, challenger;
        int capture, score;
        boolean contested;
        Objective(int id, int x, int y) { this.id = id; this.x = x; this.y = y; }
        ShadowFrame.Station snapshot() {
            return new ShadowFrame.Station(id, x, y, owner, challenger, capture, score, contested);
        }
    }

    public ShadowGameplayExtension(GameState state, ShadowRoster roster, EffectiveRules.ShadowRelay rules) {
        this(state, roster, rules, new RandomItemSpawnPolicy(rules.common().itemDropProbability(), new Random()),
                event -> { }, placement -> { }, System::nanoTime, System::currentTimeMillis);
    }

    /** Clock/drop policy tiêm để kiểm fuse, timeout và nhặt item không cần chờ thời gian thật. */
    public ShadowGameplayExtension(GameState state, ShadowRoster roster, EffectiveRules.ShadowRelay rules,
                                   ItemSpawnPolicy drops, GameEventSink events, Consumer<BombCommandResult> placements,
                                   LongSupplier nanos, LongSupplier epoch) {
        this.state = Objects.requireNonNull(state);
        this.roster = Objects.requireNonNull(roster);
        this.rules = Objects.requireNonNull(rules);
        this.nanos = Objects.requireNonNull(nanos);
        var common = rules.common();
        if (state.getPlayerCount() != 4 || state.getMap().getWidthInTiles() != common.width()
                || state.getMap().getHeightInTiles() != common.height() || state.getMap().getTileSize() != common.tilePixels()
                || roster.players().stream().anyMatch(p -> state.getPlayer(p.playerId()) == null)) {
            throw new IllegalArgumentException("World does not match Shadow roster/geometry");
        }
        var players = new LinkedHashMap<String, Integer>();
        for (var player : roster.players()) players.put(player.sessionId(), player.playerId());
        var matchRules = new MatchRules(common.durationMillis(), common.itemDropProbability(), common.maxBombQuota(),
                common.maxBombRange(), common.maxSpeed(), common.speedIncrement(), common.initialLives(), common.maxLives());
        var counter=new java.util.concurrent.atomic.AtomicInteger(1);
        IntSupplier ids=()->{int id=counter.getAndIncrement();if(id<=0)throw new IllegalStateException("Bomb IDs exhausted");return id;};
        scans=new ShadowScans(state,roster,rules.scan(),nanos,ids);
        gameplay = new MatchGameplayExtension(state, players,
                new BombRules(common.normalFuseMillis(), common.initialBombRange(), common.initialBombQuota()),
                matchRules, drops, events, placements, nanos, epoch, MatchGameplayExtension.ResultPolicy.EXTERNAL,scans,ids);
        for (int i = 0; i < ShadowArena.STATIONS.size(); i++) {
            var cell = ShadowArena.STATIONS.get(i);objectives.add(new Objective(i + 1, cell.x(), cell.y()));
        }
        publish(common.durationMillis());
    }

    @Override public void onStart() {
        if (owner != null) throw new IllegalStateException("Shadow starts once");
        owner = Thread.currentThread();started = nanos.getAsLong();scans.start(started);gameplay.onStart();publish(rules.common().durationMillis());
    }

    private void check() {
        if (owner != Thread.currentThread() || stopped) throw new IllegalStateException("Shadow outside active game thread");
    }

    @Override public void processCommand(GameCommand command) {
        check();
        if (command instanceof PlayerDisconnectedCommand) frozen.add(command.getPlayerId());
        if (command instanceof PlayerReconnectedCommand) frozen.remove(command.getPlayerId());
        if(command instanceof PlantBombCommand plant&&plant.requestId()>0){processPlant(plant);return;}
        if(command instanceof PlantBombCommand plant&&plant.bombType()==BombType.SCAN)
            throw new IllegalArgumentException("SCAN requires a positive requestId");
        gameplay.processCommand(command);
    }

    /** NORMAL/SCAN dùng chung high-water/cache, giữ qua disconnect; replay không reset cooldown/quota. */
    private void processPlant(PlantBombCommand command){
        int player=command.getPlayerId();long id=command.requestId();
        if(player<1||player>4){reject(command,"PLAYER_NOT_FOUND");return;}
        var cache=requests.computeIfAbsent(player,k->new LinkedHashMap<>());var old=cache.get(id);
        if(old!=null){if(old.type()!=command.bombType())reject(command,"REQUEST_ID_CONFLICT");else state.recordPlantResult(old.result());return;}
        if(id<=highWater.getOrDefault(player,0L)){reject(command,"REQUEST_TOO_OLD");return;}
        highWater.put(player,id);long now=elapsedMillis();
        if(now-rateStarted.getOrDefault(player,Long.MIN_VALUE/2)>=1000){rateStarted.put(player,now);rateCount.put(player,0);}
        if(rateCount.merge(player,1,Integer::sum)>5)reject(command,"RATE_LIMITED");
        else if(command.bombType()==BombType.SCAN)state.recordPlantResult(scans.plant(command,frozen.contains(player)));
        else gameplay.processCommand(command);
        cache.put(id,new Cached(command.bombType(),state.plantResults().getLast()));
        if(cache.size()>64)cache.remove(cache.keySet().iterator().next());
    }
    private void reject(PlantBombCommand command,String error){state.recordPlantResult(new PlantRequestResult(command.getPlayerId(),command.requestId(),command.bombType(),false,error,null));}

    @Override public void update(long nowMillis) {
        check();if (result != null) return;
        tick++;gameplay.update(nowMillis);scans.update();
        boolean aliveA = alive(TeamId.A), aliveB = alive(TeamId.B);
        if (!aliveA || !aliveB) {
            finish(!aliveA && !aliveB ? ShadowFrame.Reason.BOTH_TEAMS_ELIMINATED : ShadowFrame.Reason.TEAM_ELIMINATED,
                    !aliveA && !aliveB ? null : aliveA ? TeamId.A : TeamId.B);
        } else {
            // Cộng điểm của tất cả trạm trước khi xét thắng, tránh thiên lệch thứ tự duyệt trạm.
            for (var objective : objectives) updateObjective(objective);
            if (scoreA >= rules.targetScore() || scoreB >= rules.targetScore()) finish(ShadowFrame.Reason.TARGET_SCORE, leadingTeam());
            else if (elapsedMillis() >= rules.common().durationMillis()) finish(ShadowFrame.Reason.TIMEOUT, leadingTeam());
        }
        publish(Math.max(0, rules.common().durationMillis() - elapsedMillis()));
    }

    private boolean alive(TeamId team) {
        return roster.players().stream().filter(p -> p.team() == team)
                .anyMatch(p -> state.getPlayer(p.playerId()) != null && state.getPlayer(p.playerId()).isAlive());
    }

    private void updateObjective(Objective objective) {
        boolean a = false, b = false;
        for (var member : roster.players()) {
            var player = state.getPlayer(member.playerId());
            if (player == null || !player.isAlive() || frozen.contains(member.playerId())) continue;
            var cell = player.tilePosition(state.getMap());
            if (Math.abs(cell.x() - objective.x) + Math.abs(cell.y() - objective.y) <= rules.captureRadius()) {
                if (member.team() == TeamId.A) a = true; else b = true;
            }
        }
        objective.contested = a && b;
        TeamId exclusive = a == b ? null : a ? TeamId.A : TeamId.B;
        boolean captured = false;
        if (exclusive != null && exclusive != objective.owner) {
            objective.capture = exclusive == objective.challenger ? objective.capture + 1 : 1;
            objective.challenger = exclusive;
            if (objective.capture == rules.captureTicks()) {
                objective.owner = exclusive;objective.score = 0;captured = true;
                objective.capture = 0;objective.challenger = null;
            }
        } else { objective.capture = 0;objective.challenger = null; }
        boolean enemyPresent = objective.owner == TeamId.A ? b : a;
        // Trạm có chủ vẫn sinh điểm khi trống; tranh chấp chỉ tạm giữ phần lẻ điểm.
        if (!captured && objective.owner != null && !enemyPresent && ++objective.score == rules.scorePeriodTicks()) {
            if (objective.owner == TeamId.A) scoreA++; else scoreB++;
            objective.score = 0;
        }
    }

    private long elapsedMillis() { return Math.max(0, (nanos.getAsLong() - started) / 1_000_000L); }
    private TeamId leadingTeam() { return scoreA == scoreB ? null : scoreA > scoreB ? TeamId.A : TeamId.B; }

    private void finish(ShadowFrame.Reason reason, TeamId winner) {
        result = new ShadowFrame.Result(tick, winner, reason, scoreA, scoreB);state.finishMatch();scans.clear();
    }

    private void publish(long remainingMillis) {
        state.setShadowFrame(new ShadowFrame(tick, (int)((remainingMillis + 999) / 1000), scoreA, scoreB,
                objectives.stream().map(Objective::snapshot).toList(), result));
        state.setShadowScans(scans.snapshot());
    }

    /** Hủy hành chính không giả làm thắng/thua do đội bị loại; chỉ gọi trên game thread. */
    public void abort() {
        check();if (result == null) { finish(ShadowFrame.Reason.ABORTED, null);publish(Math.max(0, rules.common().durationMillis() - elapsedMillis())); }
    }

    @Override public void onStop() {
        if (owner == null || stopped) return;
        check();try { if (result == null) abort();gameplay.onStop(); }
        finally { scans.clear();requests.clear();highWater.clear();rateStarted.clear();rateCount.clear();frozen.clear();stopped = true; }
    }
}

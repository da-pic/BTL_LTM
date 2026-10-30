package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Đi qua API runtime có configuration thật, không đọc mutable world từ thread kiểm thử. */
@Timeout(15)
class ConfiguredRuntimeTest {
    private static GameMap arena() {
        var map=new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)
            if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        return map;
    }
    private static List<PlayerSpawn> spawns() {
        return List.of(new PlayerSpawn("a",1,36,36,24,24,80),new PlayerSpawn("b",2,484,420,24,24,80));
    }
    private static ModeRegistry registry() {
        return new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),new ClassicModeFactory((room,cfg)->arena(),
                cfg->cell->Optional.empty(),event->{},result->{},System::nanoTime,System::currentTimeMillis));
    }
    private static GameStateUpdate await(GameRuntime game,Predicate<GameStateUpdate> condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline){
            var update=game.pollUpdate(100,TimeUnit.MILLISECONDS);
            if(update!=null&&condition.test(update))return update;
            assertNull(game.failure());
        }
        throw new AssertionError("No matching runtime snapshot");
    }
    private static GameStateUpdate fullAfter(GameRuntime game)throws Exception {
        long barrier=game.lastPublishedTick();game.requestFullState();
        return await(game,u->u.isFullState()&&u.getTick()>barrier);
    }
    private static GameStateUpdate.PlayerData player(GameStateUpdate update,int id) {
        return update.getPlayers().stream().filter(p->p.getId()==id).findFirst().orElseThrow();
    }
    private static List<GameStateUpdate.PlayerData> details(GameStateUpdate update) {
        return update.isFullState()?update.getPlayers():update.getPlayerChanges();
    }

    @Test void configuredRuntimeKeepsInputAckFreezeUnfreezeAndImmutableSnapshots()throws Exception {
        var registry=registry();var configuration=registry.resolvePublic(GameMode.CLASSIC);
        try(var manager=new GameRuntimeManager(registry)){
            var game=manager.startGame("room",spawns(),configuration);
            var initial=await(game,GameStateUpdate::isFullState);
            assertSame(configuration,game.configuration().orElseThrow());
            assertEquals(3,initial.getMatchFrame().players().getFirst().lives());
            UUID match=game.matchId();
            assertTrue(game.submit(new MoveCommand(1,Direction.RIGHT,1)));
            var moved=await(game,u->details(u).stream().anyMatch(p->p.getId()==1&&p.getLastProcessedInput()==1));
            assertEquals(40,details(moved).stream().filter(p->p.getId()==1).findFirst().orElseThrow().getX());
            assertTrue(game.submit(new PlayerDisconnectedCommand(1)));
            assertTrue(game.submit(new MoveCommand(1,Direction.RIGHT,2)));
            await(game,u->details(u).stream().anyMatch(p->p.getId()==1&&!p.isMoving()));
            var frozen=fullAfter(game);
            assertEquals(1,player(frozen,1).getLastProcessedInput());
            assertEquals(40,player(frozen,1).getX());assertFalse(player(frozen,1).isMoving());
            assertTrue(game.submit(new PlayerReconnectedCommand(1)));
            assertTrue(game.submit(new MoveCommand(1,Direction.RIGHT,2)));
            var resumed=await(game,u->details(u).stream().anyMatch(p->p.getId()==1&&p.getLastProcessedInput()==2));
            assertEquals(44,details(resumed).stream().filter(p->p.getId()==1).findFirst().orElseThrow().getX());
            assertSame(game,manager.findGame("room").orElseThrow());assertEquals(match,game.matchId());
            assertSame(configuration,game.configuration().orElseThrow());
            assertEquals(36,player(initial,1).getX());
            assertTrue(game.lastPublishedTick()>=resumed.getTick());
            manager.stopGame("room");assertFalse(game.isThreadAlive());assertFalse(game.submit(new StopMovingCommand(1)));
        }
    }

    @Test void roomsHaveIndependentBombStateAndReplayGetsNewWorldAndMatch()throws Exception {
        var registry=registry();
        try(var manager=new GameRuntimeManager(registry)){
            var a=manager.startGame("a",spawns(),registry.resolvePublic(GameMode.CLASSIC));
            var b=manager.startGame("b",spawns()); // convenience Classic PUBLIC trên manager mới.
            var savedB=await(b,GameStateUpdate::isFullState);await(a,GameStateUpdate::isFullState);
            assertTrue(a.submit(new PlantBombCommand(1)));
            await(a,u->!u.getEntityChanges().isEmpty());
            assertTrue(fullAfter(b).getEntityChanges().isEmpty());assertTrue(savedB.getEntityChanges().isEmpty());
            var oldId=a.matchId();var oldConfiguration=a.configuration().orElseThrow().configurationId();
            manager.stopGame("a");assertFalse(a.isThreadAlive());assertTrue(b.isRunning());
            var replay=manager.startGame("a",spawns());
            assertNotEquals(oldId,replay.matchId());
            assertNotEquals(oldConfiguration,replay.configuration().orElseThrow().configurationId());
            var fresh=await(replay,GameStateUpdate::isFullState);
            assertTrue(fresh.getEntityChanges().isEmpty());assertEquals(3,fresh.getMatchFrame().players().getFirst().lives());
            manager.close();assertFalse(replay.isThreadAlive());assertFalse(b.isThreadAlive());
        }
    }

    @Test void invalidConfigurationAndSpawnAreRejectedBeforeAnyMapIsCreated() {
        var calls=new AtomicInteger();
        var factory=new ClassicModeFactory((room,cfg)->{calls.incrementAndGet();return arena();},
                cfg->cell->Optional.empty(),e->{},r->{},System::nanoTime,System::currentTimeMillis);
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),factory);
        var configuration=registry.resolvePublic(GameMode.CLASSIC);
        try(var manager=new GameRuntimeManager(registry)){
            var shadow=new MatchConfiguration(UUID.randomUUID(),GameMode.SHADOW_RELAY,"shadow-relay-v1",
                    MatchPreset.SHADOW_RELAY_V1,MatchProfile.PUBLIC,"shadow-relay-arena-v1",EffectiveRules.ShadowRelay.v1());
            assertEquals(ModeConfigurationException.Code.INVALID_CONFIGURATION,
                    assertThrows(ModeConfigurationException.class,()->manager.startGame("bad",spawns(),shadow)).code());
            assertThrows(IllegalArgumentException.class,()->manager.startGame("bad",List.of(spawns().getFirst()),configuration));
            var invalid=new PlayerSpawn("a",1,36,36,24,24,88);
            assertThrows(IllegalArgumentException.class,()->manager.startGame("bad",List.of(invalid,spawns().get(1)),configuration));
            assertThrows(IllegalArgumentException.class,()->manager.startGame("bad",List.of(spawns().getFirst(),spawns().getFirst()),configuration));
            assertEquals(0,calls.get());assertTrue(manager.findGame("bad").isEmpty());
        }
    }

    @Test void mapDimensionMismatchAndSharedMapAreRejectedWithoutStoppingTheFirstRoom()throws Exception {
        var shared=arena();
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),new ClassicModeFactory((room,cfg)->shared,
                cfg->cell->Optional.empty(),e->{},r->{},System::nanoTime,System::currentTimeMillis));
        try(var manager=new GameRuntimeManager(registry)){
            var first=manager.startGame("a",spawns());await(first,GameStateUpdate::isFullState);
            assertThrows(IllegalArgumentException.class,()->manager.startGame("b",spawns()));
            assertTrue(manager.findGame("b").isEmpty());assertTrue(first.isRunning());assertNull(first.failure());
        }
        var wrong=new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),new ClassicModeFactory((room,cfg)->new GameMap(8,8,32),
                cfg->cell->Optional.empty(),e->{},r->{},System::nanoTime,System::currentTimeMillis));
        try(var manager=new GameRuntimeManager(wrong)){
            assertThrows(IllegalArgumentException.class,()->manager.startGame("bad",spawns()));
            assertTrue(manager.findGame("bad").isEmpty());
        }
    }

    @Test void sharedExtensionIsRejectedBeforeItsOwnerCanBeChanged()throws Exception {
        var delegate=new ClassicModeFactory();
        var shared=new java.util.concurrent.atomic.AtomicReference<GameplayExtension>();
        var factory=new ModeRuntimeFactory(){
            public GameMode gameMode(){return GameMode.CLASSIC;}
            public GameMap createMap(String room,MatchConfiguration cfg){return arena();}
            public GameplayExtension createGameplay(String room,MatchConfiguration cfg,GameState state,Map<String,Integer> ids){
                if(shared.get()==null)shared.set(delegate.createGameplay(room,cfg,state,ids));
                return shared.get();
            }
        };
        try(var manager=new GameRuntimeManager(new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),factory))){
            var first=manager.startGame("a",spawns());await(first,GameStateUpdate::isFullState);
            assertThrows(IllegalArgumentException.class,()->manager.startGame("b",spawns()));
            assertTrue(manager.findGame("b").isEmpty());assertTrue(first.isRunning());assertNull(first.failure());
            fullAfter(first);assertNull(first.failure());
        }
    }

    @Test void factoryFailureLeavesNoRegisteredRuntimeAndCanBeRetriedWithFreshResources()throws Exception {
        var attempts=new AtomicInteger();
        var factory=new ClassicModeFactory((room,cfg)->{
            if(attempts.getAndIncrement()==0)throw new IllegalStateException("fixture failure");return arena();
        },cfg->cell->Optional.empty(),e->{},r->{},System::nanoTime,System::currentTimeMillis);
        try(var manager=new GameRuntimeManager(new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),factory))){
            assertThrows(IllegalStateException.class,()->manager.startGame("retry",spawns()));
            assertTrue(manager.findGame("retry").isEmpty());
            var game=manager.startGame("retry",spawns());await(game,GameStateUpdate::isFullState);
            assertNull(game.failure());assertEquals(2,attempts.get());
        }
    }

    @Test void legacyConstructorsRemainMovementOnlyAndCannotIgnoreAnExplicitConfiguration()throws Exception {
        var factories=new AtomicInteger();
        var configuration=registry().resolvePublic(GameMode.CLASSIC);
        try(var manager=new GameRuntimeManager(room->{factories.incrementAndGet();return arena();},(room,state)->GameplayExtension.none())){
            assertThrows(ModeConfigurationException.class,()->manager.startGame("bad",spawns(),configuration));
            assertEquals(0,factories.get());
            var game=manager.startGame("legacy",List.of(new PlayerSpawn("fixture",1,32,32,24,24,150)));
            var update=await(game,GameStateUpdate::isFullState);
            assertTrue(game.configuration().isEmpty());assertNull(update.getMatchFrame());assertEquals(150,player(update,1).getSpeed());
        }
        try(var manager=new GameRuntimeManager()){
            var game=manager.startGame("default",List.of(new PlayerSpawn("fixture",1,32,32,24,24,150)));
            assertNull(await(game,GameStateUpdate::isFullState).getMatchFrame());
        }
    }

    @Test void internalClassicUsesExplicitFixtureGridLivesAndSimulationPeriod()throws Exception {
        var common=ClassicRulesAdapter.capture(new BombRules(1000,1,1),
                new MatchRules(5000,0,5,8,144,8),new PlayerMovementRules(20,100),8,6,40,100,100,15000,29000);
        var factory=new ClassicModeFactory((room,cfg)->{
            var map=new GameMap(8,6,40);
            for(int y=0;y<6;y++)for(int x=0;x<8;x++)
                if(x==0||y==0||x==7||y==5)map.setTile(x,y,TileType.HARD_WALL);
            return map;
        },cfg->cell->Optional.empty(),e->{},r->{},System::nanoTime,System::currentTimeMillis);
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),factory);
        var config=registry.resolveInternalClassic(common);
        var roster=List.of(new PlayerSpawn("a",1,50,50,20,20,100),new PlayerSpawn("b",2,250,170,20,20,100));
        try(var manager=new GameRuntimeManager(registry)){
            var game=manager.startGame("fixture",roster,config);
            var initial=await(game,GameStateUpdate::isFullState);
            assertEquals(8,initial.getMapWidth());assertEquals(6,initial.getMapHeight());assertEquals(40,initial.getTileSize());
            assertEquals(1,initial.getMatchFrame().players().getFirst().lives());
            assertTrue(game.submit(new MoveCommand(1,Direction.RIGHT,1)));
            var moved=await(game,u->details(u).stream().anyMatch(p->p.getId()==1&&p.getLastProcessedInput()==1));
            assertEquals(60,details(moved).stream().filter(p->p.getId()==1).findFirst().orElseThrow().getX());
            var solo=manager.startGame("solo",List.of(roster.getFirst()),config);
            assertEquals(MatchResult.Reason.LAST_ALIVE,await(solo,GameStateUpdate::isFullState).getMatchFrame().result().reason());
        }
    }
}

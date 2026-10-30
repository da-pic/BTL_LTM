package com.bombit.server.game.mode;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.PacketEncoder;
import com.bombit.server.game.bomb.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.item.ItemType;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Đối chứng factory mới với implementation Classic cũ trên cùng clock/layout/command. */
class ClassicModeFactoryTest {
    private static GameMap arena() {
        var map = new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)
            if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        map.setTile(3,1,TileType.BREAKABLE_BLOCK);
        return map;
    }

    private static GameState state(GameMap map) {
        var state = new GameState(map);
        state.addPlayer(new Player(1,36,36,24,24,80));
        state.addPlayer(new Player(2,484,420,24,24,80));
        state.startMatch();
        return state;
    }

    @Test void factoryPreservesSnapshotsAcrossBombDamageDropAndFinalResult() throws Exception {
        var nanos=new AtomicLong();
        var bombs=new BombRules(1000,2,1);
        var rules=new MatchRules(10000,1,3,5,160,8,2,99);
        var common=ClassicRulesAdapter.publicRules(bombs,rules,new PlayerMovementRules(24,80));
        var registry=new ModeRegistry(common);
        var configuration=registry.resolvePublic(GameMode.CLASSIC);
        var outcomes=new ArrayList<BombCommandResult>();
        var originalOutcomes=new ArrayList<BombCommandResult>();
        var factory=new ClassicModeFactory((room,cfg)->arena(),cfg->cell->Optional.of(ItemType.SPEED_UP),
                event->{},outcomes::add,nanos::get,()->10000+nanos.get()/1_000_000);
        var actual=state(factory.createMap("factory",configuration));
        var original=state(arena());
        var extension=factory.createGameplay("factory",configuration,actual,Map.of("a",1,"b",2));
        var baseline=new MatchGameplayExtension(original,Map.of("a",1,"b",2),bombs,rules,
                cell->Optional.of(ItemType.SPEED_UP),event->{},originalOutcomes::add,nanos::get,()->10000+nanos.get()/1_000_000);
        var builder=new StateDeltaBuilder();var originalBuilder=new StateDeltaBuilder();
        extension.onStart();baseline.onStart();
        try {
            long[] times={0,1000,1001,2001,10000};
            for(int n=0;n<times.length;n++) {
                nanos.set(times[n]*1_000_000);
                if(n==0||n==2){extension.processCommand(new PlantBombCommand(1));baseline.processCommand(new PlantBombCommand(1));}
                extension.update(10000+times[n]);baseline.update(10000+times[n]);
                var update=builder.buildUpdate(actual,n+1,true);
                var expected=originalBuilder.buildUpdate(original,n+1,true);
                assertArrayEquals(PacketEncoder.encode(SnapshotPacketCodec.encode(expected)),
                        PacketEncoder.encode(SnapshotPacketCodec.encode(update)),"Classic differs at time "+times[n]);
                builder.commit(update);originalBuilder.commit(expected);
                if(n==1){
                    assertEquals(1,actual.getPlayer(1).getLives());
                    assertEquals(TileType.EMPTY,actual.getMap().tileAt(3,1));
                    assertTrue(actual.getEntities().stream().anyMatch(e->e.kind()==EntityData.Kind.ITEM));
                }
            }
            assertEquals(originalOutcomes,outcomes);
            assertEquals(MatchResult.Reason.LAST_ALIVE,actual.getMatchFrame().result().reason());
            assertEquals(2,actual.getMatchFrame().result().winnerPlayerId());
            assertEquals(0,actual.getPlayer(1).getLives());
        } finally {extension.onStop();baseline.onStop();}
        assertTrue(actual.getEntities().isEmpty());
        assertTrue(original.getEntities().isEmpty());
    }

    @Test void defaultFactoryCreatesFreshMapsAndWorldAdaptersForEveryMatch() {
        var factory=new ClassicModeFactory();
        var configuration=new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).resolvePublic(GameMode.CLASSIC);
        var mapA=factory.createMap("a",configuration);var mapB=factory.createMap("b",configuration);
        assertNotSame(mapA,mapB);
        mapA.setTile(2,1,TileType.BREAKABLE_BLOCK);
        assertEquals(TileType.EMPTY,mapB.tileAt(2,1)); // safe cell của generator không có vật cản.
        var a=state(mapA);var b=state(mapB);
        var extensionA=factory.createGameplay("a",configuration,a,Map.of("a",1,"b",2));
        var extensionB=factory.createGameplay("b",configuration,b,Map.of("a",1,"b",2));
        assertNotSame(extensionA,extensionB);
        extensionA.onStart();extensionB.onStart();
        try {
            extensionA.processCommand(new PlantBombCommand(1));
            assertFalse(a.getEntities().isEmpty());assertTrue(b.getEntities().isEmpty());
        } finally {extensionA.onStop();extensionB.onStop();}
    }

    @Test void shadowIsRejectedBeforeClassicResourceSuppliersRun() {
        var maps=new AtomicInteger();
        var factory=new ClassicModeFactory((room,cfg)->{maps.incrementAndGet();return arena();},
                cfg->cell->Optional.empty(),event->{},result->{},System::nanoTime,System::currentTimeMillis);
        var shadow=new MatchConfiguration(UUID.randomUUID(),GameMode.SHADOW_RELAY,"shadow-relay-v1",
                MatchPreset.SHADOW_RELAY_V1,MatchProfile.PUBLIC,"shadow-relay-arena-v1",EffectiveRules.ShadowRelay.v1());
        assertThrows(IllegalArgumentException.class,()->factory.createMap("a",shadow));
        assertThrows(IllegalArgumentException.class,()->factory.createGameplay("a",shadow,state(arena()),Map.of("a",1,"b",2)));
        assertEquals(0,maps.get());
        var wrongFactory=new ModeRuntimeFactory(){
            public GameMode gameMode(){return GameMode.SHADOW_RELAY;}
            public GameMap createMap(String room,MatchConfiguration cfg){throw new AssertionError("Must not create a map");}
            public GameplayExtension createGameplay(String room,MatchConfiguration cfg,GameState state,Map<String,Integer> players){
                throw new AssertionError("Must not create gameplay");
            }
        };
        assertEquals(ModeConfigurationException.Code.INVALID_CONFIGURATION,
                assertThrows(ModeConfigurationException.class,()->new ModeRegistry(EffectiveRules.Common.shadowRelayV1(),wrongFactory)).code());
    }
}

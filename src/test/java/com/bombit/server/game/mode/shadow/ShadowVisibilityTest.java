package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.StateDeltaBuilder;
import com.bombit.server.game.replication.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowVisibilityTest {
    @Test void wallsBlockBothSourcesBlocksOnlyNormalAndOpaqueSurfaceIsVisible(){
        var map=new GameMap(9,9,32);var source=new TilePosition(3,3);
        map.setTile(4,3,TileType.BREAKABLE_BLOCK);
        var normal=ShadowVisibility.cells(map,List.of(source),List.of());
        var scan=ShadowVisibility.cells(map,List.of(),List.of(source));
        assertTrue(normal.contains(new TilePosition(4,3)));assertFalse(normal.contains(new TilePosition(5,3)));
        assertTrue(scan.contains(new TilePosition(5,3)));assertTrue(scan.contains(new TilePosition(7,3)));
        assertFalse(scan.contains(new TilePosition(8,3)));assertFalse(normal.contains(new TilePosition(3,7)));
        map.setTile(4,3,TileType.HARD_WALL);
        assertFalse(ShadowVisibility.cells(map,List.of(),List.of(source)).contains(new TilePosition(5,3)));
    }

    @Test void supercoverRejectsSingleOpaqueCornerInAllDirections(){
        for(int dx:List.of(-1,1))for(int dy:List.of(-1,1)){
            var map=new GameMap(7,7,32);var source=new TilePosition(3,3);var target=new TilePosition(3+dx,3+dy);
            assertTrue(ShadowVisibility.cells(map,List.of(source),List.of()).contains(target));
            map.setTile(3+dx,3,TileType.HARD_WALL);
            assertFalse(ShadowVisibility.cells(map,List.of(source),List.of()).contains(target));
            assertFalse(ShadowVisibility.cells(map,List.of(),List.of(source)).contains(target));
            map.setTile(3+dx,3,TileType.EMPTY);map.setTile(3,3+dy,TileType.HARD_WALL);
            assertFalse(ShadowVisibility.cells(map,List.of(source),List.of()).contains(target));
        }
    }

    private static WorldFrame frame(ShadowScanTest.Fixture f,Set<Integer> frozen){
        return new WorldFrame(new StateDeltaBuilder().buildUpdate(f.state,f.state.getShadowFrame().tick(),true),
                f.clock.get()/1_000_000,10000,frozen,List.of(),f.state.getShadowFrame(),f.state.getShadowScans());
    }
    private static Set<Integer> details(ShadowRecipientView view){return view.snapshot().getPlayers().stream().map(p->p.getId()).collect(java.util.stream.Collectors.toSet());}

    @Test void projectorHidesEnemyDetailsStatsAckCooldownAndActorEventsButKeepsPublicWorld(){
        try(var f=new ShadowScanTest.Fixture()){
            f.state.getPlayer(1).acknowledgeInput(11);f.state.getPlayer(2).acknowledgeInput(99);
            f.plant(2,1,BombType.SCAN);f.at(1);
            f.state.recordGameEvent(new PlayerDiedEvent(10001,f.roster.player(2).sessionId(),f.roster.player(1).sessionId(),"EXPLOSION"));
            var full=frame(f,Set.of());var a=ShadowViewProjector.project(full,f.roster,1);
            assertEquals(Set.of(1,3),details(a));assertEquals(Set.of(1,3),a.snapshot().getMatchFrame().players().stream().map(MatchFrame.Stats::playerId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(11,a.lastProcessedInput());assertTrue(a.snapshot().getPlayers().stream().allMatch(p->p.getLastProcessedInput()==0));
            assertEquals(0,a.scanReadyAtMillis());assertTrue(a.scans().cooldownReadyAtMillis().isEmpty());
            assertEquals(4,a.publicPlayers().size());assertEquals(full.snapshot().getEntityChanges(),a.snapshot().getEntityChanges());
            assertEquals(1,a.scans().pending().size());assertTrue(a.snapshot().getGameNotices().isEmpty());
            var b=ShadowViewProjector.project(full,f.roster,2);assertEquals(Set.of(2,4),details(b));assertEquals(8000,b.scanReadyAtMillis());
            assertThrows(UnsupportedOperationException.class,()->a.visibleCells().clear());
            // View cũ không thay đổi khi đối thủ bước vào rồi ra tầm nhìn.
            f.move(2,3,1);f.at(2);assertTrue(details(ShadowViewProjector.project(frame(f,Set.of()),f.roster,1)).contains(2));
            f.move(2,15,1);f.at(3);assertFalse(details(ShadowViewProjector.project(frame(f,Set.of()),f.roster,1)).contains(2));
            assertEquals(Set.of(1,3),details(a));
        }
    }

    @Test void frozenOrEliminatedMembersGiveNoVisionButActiveScanSurvivesAndIsTeamScoped(){
        try(var f=new ShadowScanTest.Fixture()){
            f.move(1,8,7);f.move(2,11,7);f.plant(1,1,BombType.SCAN);
            f.game.processCommand(new PlayerDisconnectedCommand(1));f.state.getPlayer(3).setLives(0);f.at(1499);
            var before=ShadowViewProjector.project(frame(f,Set.of(1)),f.roster,3);
            assertTrue(before.visibleCells().isEmpty());assertEquals(Set.of(1,3),details(before));
            f.at(1500);var a=ShadowViewProjector.project(frame(f,Set.of(1)),f.roster,3);
            assertTrue(details(a).contains(2));assertTrue(a.visibleCells().contains(new TilePosition(12,7)));
            var b=ShadowViewProjector.project(frame(f,Set.of(1)),f.roster,2);
            assertFalse(b.visibleCells().contains(new TilePosition(4,7)));
            f.at(4500);assertTrue(ShadowViewProjector.project(frame(f,Set.of(1)),f.roster,3).visibleCells().isEmpty());
        }
    }

    @Test void changingHiddenEnemyPositionStatsAndAckDoesNotChangePrivateView(){
        try(var f=new ShadowScanTest.Fixture()){
            f.at(1);var before=ShadowViewProjector.project(frame(f,Set.of()),f.roster,1);
            f.move(2,14,2);f.state.getPlayer(2).setSpeed(96);f.state.getPlayer(2).setShield(true);
            f.state.getPlayer(2).setLives(2);f.state.getPlayer(2).acknowledgeInput(999);
            f.at(1);var after=ShadowViewProjector.project(frame(f,Set.of()),f.roster,1);
            assertEquals(details(before),details(after));assertEquals(before.visibleCells(),after.visibleCells());
            assertEquals(before.snapshot().getMatchFrame(),after.snapshot().getMatchFrame());
            assertEquals(before.publicPlayers(),after.publicPlayers());assertEquals(before.lastProcessedInput(),after.lastProcessedInput());
            assertEquals(before.snapshot().getGameNotices(),after.snapshot().getGameNotices());
            for(var p:after.snapshot().getPlayers()){
                var old=before.snapshot().getPlayers().stream().filter(v->v.getId()==p.getId()).findFirst().orElseThrow();
                assertEquals(old.getX(),p.getX());assertEquals(old.getY(),p.getY());assertEquals(old.getSpeed(),p.getSpeed());
            }
        }
    }
}

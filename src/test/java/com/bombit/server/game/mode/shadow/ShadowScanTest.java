package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowScanTest {
    static final class Fixture implements AutoCloseable {
        final AtomicLong clock=new AtomicLong();
        final GameState state=new GameState(new GameMap(17,15,32));
        final ShadowRoster roster;
        final ShadowGameplayExtension game;
        Fixture(){
            var players=new ArrayList<MatchStartMetadata.Member>();
            for(int id=1;id<=4;id++){var cell=ShadowArena.spawn(id);state.addPlayer(new Player(id,cell.x()*32+4,cell.y()*32+4,24,24,80));
                players.add(new MatchStartMetadata.Member(new UUID(0,id).toString(),id,id,"P"+id,ShadowRoster.teamForSlot(id)));}
            roster=new ShadowRoster(players);state.startMatch();
            game=new ShadowGameplayExtension(state,roster,EffectiveRules.ShadowRelay.v1(),cell->Optional.empty(),e->{},r->{},clock::get,()->10000+clock.get()/1_000_000);
            game.onStart();
        }
        void move(int id,int x,int y){state.getPlayer(id).setPosition(x*32+4,y*32+4);}
        PlantRequestResult plant(int player,long request,BombType type){game.processCommand(new PlantBombCommand(player,request,type));return state.plantResults().getLast();}
        void at(long millis){clock.set(millis*1_000_000);game.update(10000+millis);}
        ShadowScanFrame scans(){return state.getShadowScans();}
        public void close(){game.onStop();}
    }

    @Test void fuseActiveExpiryCooldownAndSharedQuotaUseAuthoritativeClock(){
        try(var f=new Fixture()){
            f.state.getMap().setTile(9,3,com.bombit.server.game.model.TileType.BREAKABLE_BLOCK);
            f.move(1,8,3);var scan=f.plant(1,1,BombType.SCAN);assertTrue(scan.accepted());f.move(1,1,1);
            assertEquals("BOMB_LIMIT_REACHED",f.plant(1,2,BombType.NORMAL).errorCode());
            f.at(1499);assertEquals(1,f.scans().pending().size());assertTrue(f.scans().active().isEmpty());
            f.at(1500);assertTrue(f.scans().pending().isEmpty());assertEquals(4500,f.scans().active().getFirst().expiresAtMillis());
            assertEquals(com.bombit.server.game.model.TileType.BREAKABLE_BLOCK,f.state.getMap().tileAt(9,3));
            assertTrue(f.state.getPlayers().stream().allMatch(p->p.getLives()==3));
            var normal=f.plant(1,3,BombType.NORMAL);assertTrue(normal.accepted());assertNotEquals(scan.bombId(),normal.bombId());
            assertEquals("SCAN_COOLDOWN",f.plant(1,4,BombType.SCAN).errorCode());
            f.at(4499);assertEquals(1,f.scans().active().size());assertEquals(8000,f.scans().cooldownReadyAtMillis().get(1));
            f.at(4500);assertTrue(f.scans().active().isEmpty());assertEquals(0,f.state.getShadowFrame().scoreA());
            f.at(8000);f.move(1,8,3);assertTrue(f.plant(1,5,BombType.SCAN).accepted());
        }
    }

    @Test void blastActivatesPendingScanEarlyWithoutDamageOrChainFromScan(){
        try(var f=new Fixture()){
            f.move(2,9,7);assertTrue(f.plant(2,1,BombType.NORMAL).accepted());
            f.at(2000);f.move(1,10,7);var scan=f.plant(1,1,BombType.SCAN);assertTrue(scan.accepted());
            f.move(4,11,7);var other=f.plant(4,1,BombType.NORMAL);f.move(3,11,7);f.move(1,1,1);
            f.at(3000);
            assertTrue(f.scans().pending().isEmpty());assertEquals(6000,f.scans().active().getFirst().expiresAtMillis());
            assertEquals(3,f.state.getPlayer(3).getLives());
            assertTrue(f.state.getEntities().stream().anyMatch(e->e.kind()==EntityData.Kind.BOMB&&e.id()==other.bombId()));
            assertTrue(f.state.getEntities().stream().noneMatch(e->e.kind()==EntityData.Kind.BOMB&&e.id()==scan.bombId()));
        }
    }

    @Test void retryConflictOldIdAndRateLimitsDoNotRepeatPlacementOrResetCooldown(){
        try(var f=new Fixture()){
            var original=f.plant(1,1,BombType.SCAN);assertTrue(original.accepted());
            f.game.processCommand(new PlayerDisconnectedCommand(1));f.game.processCommand(new PlayerReconnectedCommand(1));
            assertEquals(original,f.plant(1,1,BombType.SCAN));
            assertEquals("REQUEST_ID_CONFLICT",f.plant(1,1,BombType.NORMAL).errorCode());
            assertEquals("SCAN_COOLDOWN",f.plant(1,2,BombType.SCAN).errorCode());
            for(int id=3;id<=66;id++)f.plant(1,id,BombType.SCAN);
            assertEquals("REQUEST_TOO_OLD",f.plant(1,1,BombType.SCAN).errorCode());
            f.at(1);assertEquals(1,f.scans().pending().size());assertEquals(8000,f.scans().cooldownReadyAtMillis().get(1));
            assertThrows(IllegalArgumentException.class,()->f.game.processCommand(new PlantBombCommand(2,0,BombType.SCAN)));
        }
    }

    @Test void rejectedQuotaOrOccupiedCellDoesNotStartCooldownAndNormalCannotOverlapScan(){
        try(var f=new Fixture()){
            var normal=f.plant(1,1,BombType.NORMAL);assertTrue(normal.accepted());f.move(1,2,1);
            assertEquals("BOMB_LIMIT_REACHED",f.plant(1,2,BombType.SCAN).errorCode());
            f.at(1);assertFalse(f.scans().cooldownReadyAtMillis().containsKey(1));
            f.move(3,1,1);assertEquals("CELL_OCCUPIED",f.plant(3,1,BombType.SCAN).errorCode());
            f.move(3,8,7);assertTrue(f.plant(3,2,BombType.SCAN).accepted());
            f.move(2,8,7);assertEquals("CELL_OCCUPIED",f.plant(2,1,BombType.NORMAL).errorCode());
        }
    }

    @Test void pendingScanBlocksEntryAllowsExitAndSurvivesOwnerForfeit(){
        try(var f=new Fixture()){
            f.move(1,8,7);assertTrue(f.plant(1,1,BombType.SCAN).accepted());
            var collision=new CollisionSystem();collision.setEntities(f.state.getEntities());
            assertTrue(collision.canOccupy(f.state.getMap(),f.state.getPlayer(1),9*32+4,7*32+4));
            f.move(2,9,7);assertFalse(collision.canOccupy(f.state.getMap(),f.state.getPlayer(2),8*32+4,7*32+4));
            f.move(2,15,1); // Không để player thật ở vùng trạm khi kiểm scan không tự capture.
            f.game.processCommand(new PlayerDisconnectedCommand(1,true));f.state.removePlayer(1);
            f.at(1500);assertEquals(1,f.scans().active().size());assertEquals(TeamId.A,f.scans().active().getFirst().team());
            assertNull(f.state.getShadowFrame().result());assertEquals(0,f.state.getShadowFrame().stations().get(1).captureProgress());
            collision.setEntities(f.state.getEntities());assertTrue(collision.canOccupy(f.state.getMap(),f.state.getPlayer(2),8*32+4,7*32+4));
            f.at(4500);assertTrue(f.scans().active().isEmpty());
        }
    }
}

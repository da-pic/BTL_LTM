package com.bombit.server.game.runtime;

import com.bombit.client.game.movement.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.map.ArenaGenerator;
import com.bombit.server.game.model.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.bomb.BombSystem;
import com.bombit.server.game.movement.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementBalanceTest {
    private static Player player(PlayerSpawn spawn){return new Player(spawn.playerId(),spawn.x(),spawn.y(),spawn.width(),spawn.height(),spawn.speed());}
    @Test void centeredSpawnsLeaveClearanceAndKeepTheCorrectCellForDifferentTileSizes(){
        var rules=new PlayerMovementRules(24,80);
        for(int size:new int[]{32,40,48})for(var cell:ArenaGenerator.SPAWNS){
            var map=new GameMap(17,15,size);var p=player(rules.spawn("p",1,cell,size));
            assertEquals(cell,p.tilePosition(map));assertEquals(size/2.0,p.getX()+p.getWidth()/2-cell.x()*size);
            assertEquals(size/2.0,p.getY()+p.getHeight()/2-cell.y()*size);
            assertTrue(p.getWidth()<size);assertTrue(new CollisionSystem().canOccupy(map,p,p.getX(),p.getY()));
        }
        assertThrows(IllegalArgumentException.class,()->new PlayerMovementRules(32,80).spawn("p",1,new TilePosition(1,1),32));
        assertThrows(IllegalArgumentException.class,()->new PlayerMovementRules(24,Double.NaN));
    }
    @Test void slowerServerAndPredictionTakeTheSameCornersWithoutChangingGridGeometry(){
        var map=ArenaGenerator.create();var state=new GameState(map);
        var p=player(new PlayerMovementRules(24,80).spawn("p",1,new TilePosition(1,1),32));state.addPlayer(p);state.startMatch();
        var full=new StateDeltaBuilder().buildUpdate(state,1,true);var world=new ClientWorldState();world.apply(full);
        var prediction=new PredictionController(new GameMap(17,15,32),full.getPlayers().getFirst());prediction.updateWorld(world);
        var movement=new MovementSystem();long sequence=0;
        Direction[] path={Direction.RIGHT,Direction.DOWN,Direction.UP,Direction.LEFT};int[] steps={16,8,8,16};
        for(int n=0;n<path.length;n++)for(int i=0;i<steps[n];i++){
            var input=prediction.predict(path[n],true);assertEquals(++sequence,input.sequence());
            p.beginTick();movement.applyCommand(state,new MoveCommand(1,path[n],sequence));movement.update(state,.05);
            assertEquals(p.getX(),prediction.position().getX(),1e-9);assertEquals(p.getY(),prediction.position().getY(),1e-9);
            assertTrue(new CollisionSystem().canOccupy(map,p,p.getX(),p.getY()));
        }
        assertEquals(36,p.getX());assertEquals(36,p.getY());assertEquals(new TilePosition(1,1),p.tilePosition(map));
    }
    @Test void shoePickupsAddEightRespectTheCapAndPredictionUsesTheAuthoritativeSpeed(){
        var state=new GameState(new GameMap(8,8,32));var p=player(new PlayerMovementRules(24,80).spawn("p",1,new TilePosition(2,2),32));
        state.addPlayer(p);state.startMatch();var rules=new MatchRules(5000,1,5,8,144,8,3,99);
        var adapter=new ItemWorldAdapter(state,Map.of("p",1),rules);adapter.bindToGameThread();
        var items=new ItemSystem(cell->Optional.of(ItemType.SPEED_UP),event->{});
        for(int n=1;n<=12;n++){
            items.trySpawn(p.tilePosition(state.getMap()),n,adapter);assertTrue(items.tryPickUp("p",n,adapter));
            assertEquals(Math.min(144,80+n*8),p.getSpeed());assertTrue(state.getEntities().isEmpty());
        }
        var full=new StateDeltaBuilder().buildUpdate(state,1,true);var predicted=new PredictionController(new GameMap(8,8,32),full.getPlayers().getFirst());
        predicted.predict(Direction.RIGHT,true);p.beginTick();var movement=new MovementSystem();movement.applyCommand(state,new MoveCommand(1,Direction.RIGHT,1));movement.update(state,.05);
        assertEquals(144,predicted.position().getSpeed());assertEquals(p.getX(),predicted.position().getX(),1e-9);
    }
    @Test void bombPlacementAtCellEdgesDependsOnCenterAndTileSizeAtTheNewHitboxAndSpeed(){
        for(int size:new int[]{32,40,48})for(double offset:new double[]{-.001,0,.001}){
            var state=new GameState(new GameMap(8,8,size));
            var p=player(new PlayerMovementRules(24,80).spawn("p",1,new TilePosition(1,1),size));
            p.setPosition(2*size-12+offset,p.getY());state.addPlayer(p);state.startMatch();
            var world=new BombWorldAdapter(state,Map.of("p",1),new BombRules(1000,1,1),()->0);world.bindToGameThread();
            var bomb=new BombSystem(1000,e->{}).plantBomb("p",0,world);assertTrue(bomb.succeeded());
            assertEquals(new TilePosition(offset<0?1:2,1),bomb.bomb().position());
            assertEquals(p.tilePosition(state.getMap()),bomb.bomb().position());world.clear();
        }
    }
}

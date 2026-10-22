package com.bombit.client.game.movement;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RespawnPredictionTest {
    private static GameStateUpdate.PlayerData player(double x){
        return new GameStateUpdate.PlayerData(1,x,32,24,24,150,Direction.RIGHT,false,true,0);
    }
    @Test void respawnDiscardsOldPredictedMovementWithoutReusingSequence(){
        var prediction=new PredictionController(new GameMap(10,8,32),player(32));
        prediction.predict(Direction.RIGHT,true);prediction.predict(Direction.RIGHT,true);
        assertEquals(47,prediction.position().getX());
        prediction.respawn();prediction.reconcile(player(32));
        assertEquals(32,prediction.position().getX());assertEquals(0,prediction.pendingCount());
        assertEquals(3,prediction.predict(Direction.RIGHT,true).sequence());
    }
    @Test void remoteRespawnTeleportsInsteadOfInterpolatingThroughMap(){
        var world=new ClientWorldState();var interpolation=new InterpolationController();
        var tiles=Collections.nCopies(80,TileType.EMPTY);
        for(int n=0;n<2;n++){
            var frame=new MatchFrame(10,List.of(new MatchFrame.Stats(1,1,1,2-n,false,n)),null);
            var update=GameStateUpdate.fullStateModel(n+1,10,8,32,GameState.MatchState.RUNNING,tiles,List.of(player(n==0?200:32))).withMatchFrame(frame);
            assertEquals(ClientWorldState.Result.APPLIED,world.apply(update));interpolation.record(world);
        }
        assertEquals(32,interpolation.sample(1,1.5).x());
    }
}

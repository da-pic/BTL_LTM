package com.bombit.server.game.runtime;

import com.bombit.common.dto.BombType;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.core.*;
import com.bombit.server.game.event.BombPlacedEvent;
import com.bombit.server.game.mode.paradox.ParadoxGameplayExtension;
import com.bombit.server.game.model.Direction;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ParadoxRuntimeTest {
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"Runtime condition timed out");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realGameThreadDiscardsOldBranchButKeepsLifecycleAndPublishesFull(boolean frames) throws Exception {
        var state=new GameState(new GameMap(17,15,32));
        for(int id=1;id<=3;id++)state.addPlayer(new Player(id,id*64+4,68,24,24,80));state.startMatch();
        var gate=new CountDownLatch(1);var release=new CountDownLatch(1);
        var ids=Map.of("one",1,"two",2,"three",3);
        var game=new ParadoxGameplayExtension(state,ids,new BombRules(3000,1,3),
                new MatchRules(180000,0,5,8,144,8,3,99),42,event->{
                    if(event instanceof BombPlacedEvent){gate.countDown();try {
                        if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timeout");
                    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                },r->{},System::nanoTime,System::currentTimeMillis);
        var runtime=new GameRuntime("paradox",state,ids,game,null,frames,System::nanoTime,System::currentTimeMillis);
        try {
            runtime.start();await(()->runtime.paradoxStatus().map(s->s.simulationTick()>=45).orElse(false));
            long previousTick=runtime.lastPublishedTick();
            assertTrue(runtime.submit(new PlantBombCommand(1,1,BombType.NORMAL),0));assertTrue(gate.await(2,TimeUnit.SECONDS));
            assertTrue(runtime.submit(new RewindCommand(1,1,0),0));
            assertTrue(runtime.submit(new RewindCommand(2,1,0),0));
            assertTrue(runtime.submit(new MoveCommand(1,Direction.RIGHT,101),0));
            assertTrue(runtime.submit(new PlantBombCommand(1,2,BombType.NORMAL),0));
            assertTrue(runtime.submit(new PlayerDisconnectedCommand(2),0));
            assertTrue(runtime.submit(new PlayerDisconnectedCommand(3,true),0));
            assertTrue(runtime.submit(new PlayerReconnectedCommand(2,77),0));
            release.countDown();
            await(()->runtime.timelineEpoch()==1&&runtime.paradoxStatus().orElseThrow().results().size()>=2);
            com.bombit.server.game.movement.GameStateUpdate full;
            if(frames){
                var frame=runtime.pollWorldFrame(2,TimeUnit.SECONDS);assertNotNull(frame);full=frame.snapshot();
                assertFalse(frame.frozen().contains(2));assertEquals(77L,frame.recoveryAcks().get(2));
            }else full=runtime.pollUpdate(2,TimeUnit.SECONDS);
            assertNotNull(full);assertTrue(full.isFullState());
            assertTrue(full.getTick()>previousTick);assertEquals(1,runtime.timelineEpoch());
            var status=runtime.paradoxStatus().orElseThrow();
            assertEquals(ParadoxGameplayExtension.Outcome.ACCEPTED,status.results().getFirst().outcome());
            assertEquals(ParadoxGameplayExtension.Outcome.REWIND_BUSY,status.results().get(1).outcome());
            assertTrue(status.availableCharges().contains(2));
            assertEquals(41,status.serverTick()-status.simulationTick());
            assertEquals(2,full.getPlayers().size());assertTrue(full.getEntityChanges().isEmpty());
            assertEquals(0,full.getPlayers().stream().filter(p->p.getId()==1).findFirst().orElseThrow().getLastProcessedInput());
            // Epoch cũ đến muộn vẫn bị loại; epoch mới tiếp tục điều khiển được.
            assertTrue(runtime.submit(new MoveCommand(1,Direction.RIGHT,102),0));
            assertTrue(runtime.submit(new MoveCommand(1,Direction.RIGHT,103),1));
            boolean applied=false;long deadline=System.nanoTime()+2_000_000_000L;
            while(!applied&&System.nanoTime()<deadline){
                var update=frames?runtime.pollWorldFrame(1,TimeUnit.SECONDS).snapshot():runtime.pollUpdate(1,TimeUnit.SECONDS);
                if(update!=null)applied=java.util.stream.Stream.concat(update.getPlayers().stream(),update.getPlayerChanges().stream())
                        .anyMatch(p->p.getId()==1&&p.getLastProcessedInput()==103);
            }
            assertTrue(applied,"New timeline input must be applied");
            assertNull(runtime.failure());
        } finally {release.countDown();runtime.close();}
        assertFalse(runtime.isThreadAlive());assertNull(runtime.failure());
    }
}

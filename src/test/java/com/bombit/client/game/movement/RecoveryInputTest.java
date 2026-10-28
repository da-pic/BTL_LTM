package com.bombit.client.game.movement;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryInputTest {
    @Test void recoveryBetweenSamplesStillClearsPreviousConnectionKeys() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var generation=new java.util.concurrent.atomic.AtomicLong();var sent=new ArrayList<MovePacketCodec.Input>();
            var prediction=new PredictionController(new GameMap(3,3,32),
                    new GameStateUpdate.PlayerData(1,4,4,24,24,80,Direction.DOWN,false,true,0));
            var panel=new JPanel();
            try(var input=new InputController(panel,null,prediction,sent::add,e->{throw e;},()->true,generation::get)) {
                panel.getActionMap().get("tv2-RIGHT-true").actionPerformed(null);input.sample();
                assertTrue(sent.getFirst().moving());
                generation.incrementAndGet(); // Recovery đã xong trước lần sample tiếp theo.
                input.sample();assertFalse(sent.getLast().moving());
            }
        });
    }
    @Test void pausedKeyboardDoesNotPredictOrReplayHeldKeysAfterRecovery() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            var ready=new AtomicBoolean(true);var sent=new ArrayList<MovePacketCodec.Input>();
            var prediction=new PredictionController(new GameMap(3,3,32),
                    new GameStateUpdate.PlayerData(1,4,4,24,24,80,Direction.DOWN,false,true,0));
            var panel=new JPanel();
            try(var input=new InputController(panel,null,prediction,sent::add,e->{throw e;},ready::get)) {
                panel.getActionMap().get("tv2-RIGHT-true").actionPerformed(null);input.sample();
                assertTrue(sent.getFirst().moving());assertEquals(1,prediction.pendingCount());
                ready.set(false);input.sample();
                panel.getActionMap().get("tv2-DOWN-true").actionPerformed(null);input.sample();
                assertEquals(1,sent.size());assertEquals(1,prediction.pendingCount());
                ready.set(true);input.sample();
                assertFalse(sent.getLast().moving(),"Held keys from offline interval must be cleared");
            }
        });
    }
}

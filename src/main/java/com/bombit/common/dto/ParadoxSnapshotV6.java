package com.bombit.common.dto;

import com.bombit.server.game.movement.GameStateUpdate;
import java.util.*;

/** Tick snapshot vẫn tăng, chỉ tick mô phỏng bên trong mode được rewind. */
public record ParadoxSnapshotV6(GameStateUpdate update,long simulationTick,long matchTimeMillis,
        List<ClassicSnapshotV6.PublicPlayer> publicPlayers,long lastProcessedInput,boolean inputEnabled,
        ParadoxFrame paradox) implements RecipientSnapshotV6 {
    public ParadoxSnapshotV6 {
        publicPlayers=List.copyOf(publicPlayers);Objects.requireNonNull(update);Objects.requireNonNull(paradox);
        if(simulationTick!=paradox.simulationTick()||simulationTick>update.getTick()||matchTimeMillis<0
                ||lastProcessedInput<0||publicPlayers.isEmpty()||publicPlayers.size()>4)throw new IllegalArgumentException("Invalid Paradox snapshot");
    }
}

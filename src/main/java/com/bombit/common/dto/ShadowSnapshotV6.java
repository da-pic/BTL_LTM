package com.bombit.common.dto;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.GameStateUpdate;
import java.util.List;
import java.util.Set;

/** Update của view riêng; không giữ WorldFrame hoặc private detail ngoài quyền người nhận. */
public record ShadowSnapshotV6(GameStateUpdate update,long simulationTick,long matchTimeMillis,
                                List<ClassicSnapshotV6.PublicPlayer> publicPlayers,long lastProcessedInput,
                                boolean inputEnabled,ShadowFrame objectives,Set<TilePosition> visibleCells,
                                long scanReadyAtMillis) implements RecipientSnapshotV6 {
    public ShadowSnapshotV6 {
        publicPlayers=List.copyOf(publicPlayers);visibleCells=Set.copyOf(visibleCells);
        if(update==null||simulationTick!=update.getTick()||simulationTick<1||matchTimeMillis<0||lastProcessedInput<0
                ||publicPlayers.size()!=4||objectives==null||scanReadyAtMillis<0)throw new IllegalArgumentException("Invalid Shadow update");
    }
}

package com.bombit.common.dto;

import com.bombit.server.game.movement.GameStateUpdate;
import java.util.List;

/** Header/self/public roster dùng chung; phần mode và quyền quan sát vẫn tách riêng. */
public sealed interface RecipientSnapshotV6 permits ClassicSnapshotV6,ShadowSnapshotV6,ParadoxSnapshotV6 {
    GameStateUpdate update();
    long simulationTick();
    long matchTimeMillis();
    List<ClassicSnapshotV6.PublicPlayer> publicPlayers();
    long lastProcessedInput();
    boolean inputEnabled();
}

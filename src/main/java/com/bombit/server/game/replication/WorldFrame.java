package com.bombit.server.game.replication;

import com.bombit.server.game.movement.GameStateUpdate;
import java.util.Set;

/** Ảnh đầy đủ được chụp trên game thread; khác network delta và checkpoint timer/RNG. */
public record WorldFrame(GameStateUpdate snapshot,long matchTimeMillis,long epochOrigin,Set<Integer> frozen,
                          java.util.List<com.bombit.common.dto.PlantRequestResult> plantResults,
                          com.bombit.common.dto.ShadowFrame shadow,com.bombit.common.dto.ShadowScanFrame scans,
                          java.util.Map<Integer,Long> recoveryAcks, com.bombit.common.dto.ParadoxFrame paradox,
                          java.util.List<com.bombit.server.game.mode.paradox.ParadoxGameplayExtension.RewindResult> rewindResults) {
    public WorldFrame(GameStateUpdate snapshot,long matchTimeMillis,long epochOrigin,Set<Integer> frozen,
            java.util.List<com.bombit.common.dto.PlantRequestResult> results,com.bombit.common.dto.ShadowFrame shadow,
            com.bombit.common.dto.ShadowScanFrame scans,java.util.Map<Integer,Long> acks){this(snapshot,matchTimeMillis,epochOrigin,frozen,results,shadow,scans,acks,null,java.util.List.of());}
    public WorldFrame(GameStateUpdate snapshot,long matchTimeMillis,long epochOrigin,Set<Integer> frozen,
                      java.util.List<com.bombit.common.dto.PlantRequestResult> results,com.bombit.common.dto.ShadowFrame shadow,com.bombit.common.dto.ShadowScanFrame scans){this(snapshot,matchTimeMillis,epochOrigin,frozen,results,shadow,scans,java.util.Map.of());}
    public WorldFrame(GameStateUpdate snapshot,long matchTimeMillis,long epochOrigin,Set<Integer> frozen,
                      java.util.List<com.bombit.common.dto.PlantRequestResult> plantResults){this(snapshot,matchTimeMillis,epochOrigin,frozen,plantResults,null,null);}
    public WorldFrame {frozen=Set.copyOf(frozen);plantResults=java.util.List.copyOf(plantResults);recoveryAcks=java.util.Map.copyOf(recoveryAcks);rewindResults=java.util.List.copyOf(rewindResults);if(snapshot==null||!snapshot.isFullState()||matchTimeMillis<0)throw new IllegalArgumentException("World frame must be complete");}
}

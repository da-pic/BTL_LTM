package com.bombit.common.dto;

import com.bombit.server.game.movement.GameStateUpdate;
import java.util.List;

/** Recipient update Classic đã chiếu; không phải checkpoint hoặc world mutable. */
public record ClassicSnapshotV6(GameStateUpdate update,long simulationTick,long matchTimeMillis,
                                 List<PublicPlayer> publicPlayers,long lastProcessedInput,boolean inputEnabled) implements RecipientSnapshotV6 {
    public record PublicPlayer(int playerId,int lifeState,int connectionState){
        public PublicPlayer{if(playerId<=0||lifeState<0||lifeState>2||connectionState<0||connectionState>2)throw new IllegalArgumentException("Invalid public player");}
    }
    public ClassicSnapshotV6 {publicPlayers=List.copyOf(publicPlayers);if(update==null||simulationTick!=update.getTick()||simulationTick<1||matchTimeMillis<0||lastProcessedInput<0||publicPlayers.isEmpty()||publicPlayers.size()>4)throw new IllegalArgumentException("Invalid recipient update");}
}

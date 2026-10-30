package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Mở lại input và yêu cầu full state; handshake xác thực reconnect thuộc TV1. */
public final class PlayerReconnectedCommand extends GameCommand {
    private final long recoveryId;
    public PlayerReconnectedCommand(int playerId) { this(playerId,0); }
    public PlayerReconnectedCommand(int playerId,long recoveryId) { super(playerId,Direction.DOWN,false,0);if(recoveryId<0)throw new IllegalArgumentException("Invalid recovery barrier");this.recoveryId=recoveryId; }
    public long recoveryId(){return recoveryId;}
}

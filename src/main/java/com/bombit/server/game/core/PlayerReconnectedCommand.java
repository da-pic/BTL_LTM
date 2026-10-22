package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Mở lại input và yêu cầu full state; handshake xác thực reconnect thuộc TV1. */
public final class PlayerReconnectedCommand extends GameCommand {
    public PlayerReconnectedCommand(int playerId) { super(playerId,Direction.DOWN,false,0); }
}

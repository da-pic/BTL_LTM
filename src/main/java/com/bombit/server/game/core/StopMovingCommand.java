package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Dừng movement. Giữ hướng cuối để server/client replay cùng một mẫu input. */
public final class StopMovingCommand extends GameCommand {
    public StopMovingCommand(int playerId,Direction direction,long sequence) { super(playerId,direction,false,sequence); }
    public StopMovingCommand(int playerId) { this(playerId,Direction.DOWN,0); }
}

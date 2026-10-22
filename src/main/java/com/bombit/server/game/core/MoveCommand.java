package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Ý định di chuyển bất biến; sequence>0 là mẫu 50 ms, sequence=0 là giữ hướng liên tục.
 * ID số là ID nội bộ trận; runtime ánh xạ UUID session qua playerId(String).
 */
public final class MoveCommand extends GameCommand {
    public MoveCommand(int playerId,Direction direction,long sequence) { super(playerId,direction,true,sequence); }
    public MoveCommand(int playerId,Direction direction) { this(playerId,direction,0); }
}

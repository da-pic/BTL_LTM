package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Đóng băng hoặc gỡ player trên GameLoop. remove=true dùng khi hết thời hạn reconnect. */
public final class PlayerDisconnectedCommand extends GameCommand {
    private final boolean remove;
    public PlayerDisconnectedCommand(int playerId,boolean remove) {
        super(playerId,Direction.DOWN,false,0); this.remove=remove;
    }
    public PlayerDisconnectedCommand(int playerId) { this(playerId,false); }
    public boolean removePlayer() { return remove; }
}

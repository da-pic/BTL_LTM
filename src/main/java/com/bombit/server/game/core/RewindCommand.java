package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** Ý định rewind; identity do handler xác thực, epoch không suy ra từ thời điểm enqueue. */
public final class RewindCommand extends GameCommand {
    private final long requestId, timelineEpoch;
    public RewindCommand(int playerId, long requestId, long timelineEpoch) {
        super(playerId, Direction.DOWN, false, 0);
        if (requestId <= 0 || timelineEpoch < 0) throw new IllegalArgumentException("Invalid rewind request");
        this.requestId = requestId; this.timelineEpoch = timelineEpoch;
    }
    public long requestId() { return requestId; }
    public long timelineEpoch() { return timelineEpoch; }
}

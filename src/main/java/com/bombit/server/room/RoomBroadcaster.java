package com.bombit.server.room;

import com.bombit.server.network.OutboundMessage;

public interface RoomBroadcaster {
    void broadcast(String roomId, OutboundMessage message);

    void broadcastExcept(
        String roomId,
        String excludedPlayerId,
        OutboundMessage message
    );
}

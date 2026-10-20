package com.bombit.server.room;

import com.bombit.server.network.OutboundMessage;

@FunctionalInterface
public interface RoomBroadcaster {
    void broadcast(String roomId, OutboundMessage message);
}

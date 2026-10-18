package server.room;

import server.network.OutboundMessage;

@FunctionalInterface
public interface RoomBroadcaster {
    void broadcast(String roomId, OutboundMessage message);
}

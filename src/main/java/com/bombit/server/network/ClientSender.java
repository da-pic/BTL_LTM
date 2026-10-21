package com.bombit.server.network;

public interface ClientSender {
    void sendToPlayer(String playerId, OutboundMessage message);
}

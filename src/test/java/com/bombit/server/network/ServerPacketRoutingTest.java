package com.bombit.server.network;

import org.junit.jupiter.api.Test;
import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.PacketDecoder;
import com.bombit.common.protocol.PacketEncoder;
import com.bombit.common.protocol.PacketType;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class ServerPacketRoutingTest {

    @Test
    void endToEndConnectAndCreateRoom() throws Exception {
        try (Server server = new Server(0)) {
            int port = server.getPort();
            Thread serverThread = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            try (Socket clientSocket = new Socket("127.0.0.1", port)) {
                // 1. Handshake with CONNECT
                Packet connectPacket = new Packet(PacketType.CONNECT, "{\"playerName\":\"MaiPlayer\"}");
                PacketEncoder.encode(connectPacket, clientSocket.getOutputStream());

                Packet ack = PacketDecoder.decode(clientSocket.getInputStream());
                assertEquals(PacketType.CONNECT_ACK, ack.getType());
                assertTrue(ack.getJsonPayload().contains("MaiPlayer"));
                String playerId = ServerPacketDispatcher.extractJsonString(ack.getJsonPayload(), "playerId");
                assertNotNull(playerId);

                // 2. Create room
                Packet createRoom = new Packet(PacketType.CREATE_ROOM, "{\"roomName\":\"TestRoom\"}");
                PacketEncoder.encode(createRoom, clientSocket.getOutputStream());

                Packet roomState = PacketDecoder.decode(clientSocket.getInputStream());
                assertEquals(PacketType.ROOM_STATE, roomState.getType());
                assertTrue(roomState.getJsonPayload().contains("TestRoom"));
                assertTrue(roomState.getJsonPayload().contains(playerId));

                // 3. Ping / Pong
                Packet ping = new Packet(PacketType.PING, "{\"pingId\":42}");
                PacketEncoder.encode(ping, clientSocket.getOutputStream());

                Packet pong = PacketDecoder.decode(clientSocket.getInputStream());
                assertEquals(PacketType.PONG, pong.getType());
                assertEquals("{\"pingId\":42}", pong.getJsonPayload());
            }
        }
    }
}

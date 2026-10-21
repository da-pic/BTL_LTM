package com.bombit.server.network;

import org.junit.jupiter.api.Test;
import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.PacketDecoder;
import com.bombit.common.protocol.PacketEncoder;
import com.bombit.common.protocol.PacketType;

import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerTcpLifecycleTest {

    @Test
    void malformedClientDoesNotStopServer() throws Exception {
        // Port 0 picks any available ephemeral port
        try (Server server = new Server(0)) {
            int port = server.getPort();
            Thread serverThread = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Client 1: connects and sends completely invalid garbage bytes, then closes
            try (Socket badClient = new Socket("127.0.0.1", port)) {
                OutputStream out = badClient.getOutputStream();
                out.write(new byte[]{0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x02});
                out.flush();
            }

            // Client 2: connects normally and sends a valid PING packet
            try (Socket goodClient = new Socket("127.0.0.1", port)) {
                Packet ping = new Packet(PacketType.PING, "{\"pingId\":99}");
                PacketEncoder.encode(ping, goodClient.getOutputStream());

                Packet response = PacketDecoder.decode(goodClient.getInputStream());
                assertNotNull(response);
                assertEquals(PacketType.PONG, response.getType());
                assertEquals("{\"pingId\":99}", response.getJsonPayload());
            }


        }
    }

    @Test
    void abruptClientCloseHandledGracefully() throws Exception {
        try (Server server = new Server(0)) {
            int port = server.getPort();
            Thread serverThread = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            // Client connects and abruptly terminates socket
            Socket socket = new Socket("127.0.0.1", port);
            socket.setSoLinger(true, 0); // Force TCP RST
            socket.close();

            Thread.sleep(100);

            // Another client connects and operates normally
            try (Socket normalClient = new Socket("127.0.0.1", port)) {
                Packet ping = new Packet(PacketType.PING, "{\"check\":1}");
                PacketEncoder.encode(ping, normalClient.getOutputStream());

                Packet response = PacketDecoder.decode(normalClient.getInputStream());
                assertEquals(PacketType.PONG, response.getType());
            }
        }
    }
}

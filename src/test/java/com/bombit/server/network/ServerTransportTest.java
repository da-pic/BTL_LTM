package com.bombit.server.network;

import com.bombit.common.protocol.*;
import com.bombit.server.session.SessionState;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ServerTransportTest {
    private static final Packet CONNECT = new Packet(PacketType.CONNECT,
            "{\"playerName\":\"Chinh\",\"protocolVersion\":2}");
    private static final Packet PING = new Packet(PacketType.PING, "{\"pingId\":7}");

    @Test void fragmentedHandshakeWaitsForCompleteFrame() throws Exception {
        try (var fixture = new Fixture(); var client = fixture.open()) {
            byte[] frame = PacketEncoder.encode(CONNECT);
            client.getOutputStream().write(frame, 0, 3); client.getOutputStream().flush();
            client.setSoTimeout(80);
            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
            client.setSoTimeout(2_000);
            for (int i = 3; i < frame.length; i++) {
                client.getOutputStream().write(frame[i]); client.getOutputStream().flush();
            }
            Packet ack = read(client);
            assertEquals(PacketType.CONNECT_ACK, ack.getType());
            String id = field(ack, "playerId"); UUID.fromString(id); UUID.fromString(field(ack, "reconnectToken"));
            var session = fixture.server.getSessionManager().find(id).orElseThrow();
            assertEquals("Chinh", session.getUsername());
            assertEquals(SessionState.IN_LOBBY, session.getState());
            assertTrue(ack.getJsonPayload().contains("\"protocolVersion\":2"));
            send(client, PING); assertEquals(PING.getJsonPayload(), read(client).getJsonPayload());
        }
    }

    @Test void coalescedFramesPreserveWriterOrderAndDecodedNames() throws Exception {
        try (var fixture = new Fixture(); var client = fixture.open()) {
            var output = new ByteArrayOutputStream();
            PacketEncoder.encode(new Packet(PacketType.CONNECT,
                    "{ \"protocolVersion\" : 2, \"playerName\" : \"Mai \\\"M\\\"\" }"), output);
            PacketEncoder.encode(PING, output);
            PacketEncoder.encode(new Packet(PacketType.PING, "{\"pingId\":8}"), output);
            client.getOutputStream().write(output.toByteArray()); client.getOutputStream().flush();
            String id = field(read(client), "playerId");
            assertEquals("Mai \"M\"", fixture.server.getSessionManager().find(id).orElseThrow().getUsername());
            assertEquals(new Packet(PacketType.PONG, "{\"pingId\":7}"), read(client));
            assertEquals(new Packet(PacketType.PONG, "{\"pingId\":8}"), read(client));
        }
    }

    @Test void missingHandshakeAndUnsupportedVersionReturnErrorAndCleanup() throws Exception {
        try (var fixture = new Fixture()) {
            for (var request : List.of(PING, new Packet(PacketType.CONNECT,
                    "{\"playerName\":\"old\",\"protocolVersion\":1}"))) {
                try (var client = fixture.open()) {
                    send(client, request);
                    error(read(client), request == PING ? "NOT_CONNECTED" : "UNSUPPORTED_VERSION");
                    closed(client);
                }
                await(() -> fixture.server.getSessionManager().snapshot().isEmpty()
                        && fixture.server.getConnectionManager().count() == 0);
            }
            try (var healthy = fixture.open()) {
                send(healthy, CONNECT); assertEquals(PacketType.CONNECT_ACK, read(healthy).getType());
            }
        }
    }

    @Test void invalidConnectObjectsNeverBecomeAuthenticated() throws Exception {
        try (var fixture = new Fixture()) {
            for (String json : List.of("{}", "{\"playerName\":null,\"protocolVersion\":2}",
                    "{\"playerName\":\"x\",\"protocolVersion\":\"2\"}",
                    "{\"playerName\":\"x\",\"playerName\":\"y\",\"protocolVersion\":2}",
                    "{\"playerName\":\"x\\n\",\"protocolVersion\":2,\"extra\":true}",
                    "{\"playerName\":\"x\\n\",\"protocolVersion\":2}",
                    "{\"playerName\":\"\\uD800\",\"protocolVersion\":2}",
                    "{\"playerName\":\"x\",\"protocolVersion\":2,}",
                    "{\"playerName\":{},\"protocolVersion\":2}")) {
                try (var client = fixture.open()) {
                    send(client, new Packet(PacketType.CONNECT, json));
                    error(read(client), "INVALID_PACKET"); closed(client);
                }
                await(() -> fixture.server.getSessionManager().snapshot().isEmpty());
            }
        }
    }

    @Test void invalidPacketsAndUnknownTypesDoNotKillConnectionOrRefreshHeartbeat() throws Exception {
        try (var fixture = new Fixture(); var client = fixture.open()) {
            send(client, CONNECT); String id = field(read(client), "playerId");
            var session = fixture.server.getSessionManager().find(id).orElseThrow();
            long heartbeat = session.getLastHeartbeatNanos();
            send(client, new Packet(PacketType.PING, "{\"pingId\":1,\"pingId\":2}"));
            error(read(client), "INVALID_PACKET");
            send(client, new Packet((short) 0x7fff, "{}")); error(read(client), "UNKNOWN_MESSAGE");
            send(client, new Packet(PacketType.CREATE_ROOM, "{}")); error(read(client), "UNKNOWN_MESSAGE");
            send(client, CONNECT); error(read(client), "INVALID_PACKET");
            assertEquals(heartbeat, session.getLastHeartbeatNanos());
            send(client, PING); assertEquals(new Packet(PacketType.PONG, PING.getJsonPayload()), read(client));
            assertTrue(session.getLastHeartbeatNanos() > heartbeat);
            assertFalse(fixture.server.getRouter().hasHandler(PacketType.MOVE));
        }
    }

    @Test void invalidLengthsAndUtf8CloseAndReleaseProvisionalConnections() throws Exception {
        try (var fixture = new Fixture()) {
            for (byte[] bytes : List.of(new byte[]{0,0,0,1}, new byte[]{0,16,0,1},
                    new byte[]{0,0,0,3,0,0x40,(byte) 0xc0})) {
                try (var client = fixture.open()) {
                    client.getOutputStream().write(bytes); client.getOutputStream().flush(); closed(client);
                }
                await(() -> fixture.server.getConnectionManager().count() == 0
                        && fixture.server.getSessionManager().snapshot().isEmpty());
            }
        }
    }

    @Test void truncatedFrameEofAndGracefulDisconnectHaveDifferentSessionLifecycles() throws Exception {
        try (var fixture = new Fixture()) {
            String id;
            try (var client = fixture.open()) {
                send(client, CONNECT); id = field(read(client), "playerId");
                byte[] frame = PacketEncoder.encode(PING);
                client.getOutputStream().write(frame, 0, frame.length - 2); client.shutdownOutput(); closed(client);
            }
            await(() -> fixture.server.getConnectionManager().count() == 0);
            assertEquals(SessionState.DISCONNECTED, fixture.server.getSessionManager().find(id).orElseThrow().getState());
            try (var client = fixture.open()) {
                send(client, CONNECT); String gracefulId = field(read(client), "playerId");
                send(client, new Packet(PacketType.DISCONNECT, "{\"reason\":\"USER_QUIT\"}")); closed(client);
                await(() -> fixture.server.getSessionManager().find(gracefulId).isEmpty());
            }
        }
    }

    @Test void unfinishedFrameHasBoundedDeadlineAndNoSessionLeak() throws Exception {
        try (var fixture = new Fixture(150); var client = fixture.open()) {
            client.getOutputStream().write(new byte[]{0,0}); client.getOutputStream().flush();
            closed(client);
            await(() -> fixture.server.getSessionManager().snapshot().isEmpty()
                    && fixture.server.getConnectionManager().count() == 0);
        }
    }

    @Test void concurrentSendsNeverInterleaveFrames() throws Exception {
        try (var fixture = new Fixture(); var client = fixture.open()) {
            send(client, CONNECT); String id = field(read(client), "playerId");
            var session = fixture.server.getSessionManager().find(id).orElseThrow();
            var writer = fixture.server.getConnectionManager().find(id).orElseThrow();
            var pool = Executors.newFixedThreadPool(2);
            try {
                var tasks = new ArrayList<Future<?>>();
                for (int producer = 0; producer < 2; producer++) {
                    int base = producer * 20;
                    tasks.add(pool.submit(() -> {
                        for (int n = 0; n < 20; n++) writer.send(session, OutboundMessage.fromPacket(
                                new Packet(PacketType.PONG, "{\"pingId\":" + (base + n) + "}")));
                    }));
                }
                var bodies = new HashSet<String>();
                for (int n = 0; n < 40; n++) { Packet packet = read(client); assertEquals(PacketType.PONG, packet.getType()); bodies.add(packet.getJsonPayload()); }
                for (var task : tasks) task.get(2, TimeUnit.SECONDS);
                assertEquals(40, bodies.size());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void shutdownUnblocksPartialReadersAndJoinsTheirWriters() throws Exception {
        try (var fixture = new Fixture(); var pending = fixture.open(); var active = fixture.open()) {
            send(active, CONNECT); String id = field(read(active), "playerId");
            pending.getOutputStream().write(new byte[]{0,0}); pending.getOutputStream().flush();
            active.getOutputStream().write(new byte[]{0,0,0,50,0}); active.getOutputStream().flush();
            await(() -> fixture.server.getConnectionManager().count() == 2);
            var writers = fixture.server.getSessionManager().snapshot().keySet().stream()
                    .map(key -> fixture.server.getConnectionManager().find(key).orElseThrow()).toList();
            assertTimeoutPreemptively(Duration.ofSeconds(4), fixture::close);
            assertTrue(writers.stream().noneMatch(SessionWriter::isAlive));
            assertTrue(fixture.server.getSessionManager().find(id).isEmpty());
            closed(active); closed(pending);
        }
    }

    private static void send(Socket socket, Packet packet) throws IOException { PacketEncoder.encode(packet, socket.getOutputStream()); }
    private static Packet read(Socket socket) throws IOException { return PacketDecoder.decode(socket.getInputStream()); }
    private static String field(Packet packet, String name) {
        var match = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(packet.getJsonPayload());
        assertTrue(match.find(), packet.toString()); return match.group(1);
    }
    private static void error(Packet packet, String code) { assertEquals(PacketType.ERROR, packet.getType()); assertTrue(packet.getJsonPayload().contains("\"errorCode\":\"" + code + "\"")); }
    private static void closed(Socket socket) throws IOException {
        try { assertEquals(-1, socket.getInputStream().read()); }
        catch (SocketException reset) { /* Both EOF and RST are valid closure signals. */ }
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(ready.getAsBoolean(), "Timed out waiting for cleanup");
    }
    private static final class Fixture implements AutoCloseable {
        final Server server;
        final Thread thread;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Fixture() throws IOException { this(5_000); }
        Fixture(int timeout) throws IOException {
            server = new Server(0, timeout);
            thread = new Thread(() -> { try { server.start(); } catch (Throwable error) { failure.set(error); } }, "transport-test-accept");
            thread.start();
        }
        Socket open() throws IOException { var socket = new Socket("127.0.0.1", server.getPort()); socket.setSoTimeout(2_000); return socket; }
        @Override public void close() throws Exception {
            server.close(); thread.join(2_000);
            assertFalse(thread.isAlive()); assertNull(failure.get());
            assertTrue(server.getConnectionFailure().isEmpty(), () -> "Unhandled reader failure: " + server.getConnectionFailure());
            assertEquals(0, server.getConnectionManager().count());
            assertTrue(server.getSessionManager().snapshot().isEmpty());
        }
    }
}

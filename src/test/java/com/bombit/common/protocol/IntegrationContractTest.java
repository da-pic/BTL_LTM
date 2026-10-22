package com.bombit.common.protocol;

import com.bombit.common.dto.MatchContext;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.GameStateUpdate;
import com.bombit.server.game.movement.MovePacketCodec;
import com.bombit.server.game.movement.SnapshotPacketCodec;
import com.bombit.server.game.runtime.GameRuntimeManager;
import com.bombit.server.game.runtime.PlayerSpawn;
import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationContractTest {
    private static final UUID MATCH = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test void framingGoldenBytesAndFragmentedFrames() throws Exception {
        var ping = new Packet(PacketType.PING, "{}");
        byte[] golden = {0, 0, 0, 4, 0, 0x40, 0x7b, 0x7d};
        assertArrayEquals(golden, PacketEncoder.encode(ping));
        byte[] pair = new byte[golden.length * 2];
        System.arraycopy(golden, 0, pair, 0, golden.length);
        System.arraycopy(golden, 0, pair, golden.length, golden.length);
        var stream = new ByteArrayInputStream(pair) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        assertEquals(ping, PacketDecoder.decode(stream));
        assertEquals(ping, PacketDecoder.decode(stream));
        assertEquals(0, stream.available());
    }

    @Test void scopedMovePreservesLegacyCodecAndRejectsPreviousMatch() throws Exception {
        var input = new MovePacketCodec.Input(7, Direction.RIGHT, false);
        Packet legacy = MovePacketCodec.encode(input);
        assertEquals("{\"sequence\":7,\"direction\":\"RIGHT\",\"moving\":false}", legacy.getJsonPayload());
        Packet scoped = MatchEnvelopeCodec.wrap(MATCH, legacy);
        assertEquals("{\"matchId\":\"" + MATCH + "\",\"sequence\":7,\"direction\":\"RIGHT\",\"moving\":false}",
                scoped.getJsonPayload());
        assertEquals(input, MovePacketCodec.decode(MatchEnvelopeCodec.unwrap(scoped, MATCH)));
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.unwrap(scoped, UUID.randomUUID()));
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.unwrap(legacy, MATCH));
    }

    @Test void snapshotGoldenBodyUnchangedAndFullSyncUsesSameBody() throws Exception {
        var full = full();
        Packet legacy = SnapshotPacketCodec.encode(full);
        assertEquals("{\"snapshotVersion\":2,\"data\":\""
                + "AAAAAAAAAAAB//////////8AAAABAAAAAQAAACABAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                + "\"}", legacy.getJsonPayload());
        Packet sync = SnapshotPacketCodec.encodeFullSync(full);
        assertEquals(PacketType.FULL_STATE_SYNC, sync.getType());
        assertEquals(legacy.getJsonPayload(), sync.getJsonPayload());
        var decoded = SnapshotPacketCodec.decode(MatchEnvelopeCodec.unwrap(MatchEnvelopeCodec.wrap(MATCH, sync), MATCH));
        assertTrue(decoded.isFullState());
        assertEquals(1, decoded.getTick());
        assertEquals(List.of(TileType.EMPTY), decoded.mapTiles());
        var delta = GameStateUpdate.delta(2, 1, 1, 1, 32, GameState.MatchState.RUNNING,
                false, List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> SnapshotPacketCodec.encodeFullSync(delta));
        assertThrows(IOException.class, () -> SnapshotPacketCodec.decode(new Packet(PacketType.FULL_STATE_SYNC,
                SnapshotPacketCodec.encode(delta).getJsonPayload())));
    }

    @Test void resyncHasTypedReasonAndBaselineAndUniquePacketCode() throws Exception {
        for (var reason : ResyncPacketCodec.Reason.values()) {
            var request = new ResyncPacketCodec.Request(-1, reason);
            var packet = MatchEnvelopeCodec.wrap(MATCH, ResyncPacketCodec.encode(request));
            assertEquals(request, ResyncPacketCodec.decode(MatchEnvelopeCodec.unwrap(packet, MATCH)));
        }
        assertEquals(0x0034, PacketType.REQUEST_FULL_STATE);
        assertEquals("REQUEST_FULL_STATE", PacketType.nameOf(PacketType.REQUEST_FULL_STATE));
        assertThrows(IllegalArgumentException.class, () -> new ResyncPacketCodec.Request(-2, ResyncPacketCodec.Reason.COLD_START));
        assertThrows(IllegalArgumentException.class, () -> ResyncPacketCodec.decode(new Packet(PacketType.REQUEST_FULL_STATE,
                "{\"lastAppliedTick\":9223372036854775808,\"reason\":\"RECONNECT\"}")));
        var codes = new java.util.HashSet<Short>();
        for (var field : PacketType.class.getFields()) {
            if (field.getType() == short.class) assertTrue(codes.add(field.getShort(null)), field.getName());
        }
    }

    @Test void mappingIsImmutableAndCannotAliasPlayers() {
        var players = new ArrayList<>(List.of(new MatchContext.PlayerIdentity("session-a", 1)));
        var context = new MatchContext("room-a", MATCH, players);
        players.clear();
        assertEquals(1, context.playerId("session-a").orElseThrow());
        assertTrue(context.playerId("someone-else").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> context.players().clear());
        assertThrows(IllegalArgumentException.class, () -> new MatchContext("room-a", MATCH, List.of(
                new MatchContext.PlayerIdentity("a", 1), new MatchContext.PlayerIdentity("b", 1))));
        assertThrows(IllegalArgumentException.class, () -> new MatchContext("room-a", MATCH, List.of(
                new MatchContext.PlayerIdentity("a", 1), new MatchContext.PlayerIdentity("a", 2))));
    }

    @Test void runtimeReplayGetsNewEpochWithoutChangingOldApi() {
        try (var manager = new GameRuntimeManager()) {
            var players = List.of(new PlayerSpawn("session-a", 1, 32, 32, 24, 24, 100));
            var first = manager.startGame("room-a", players);
            UUID firstMatch = first.matchId();
            manager.stopGame("room-a");
            var second = manager.startGame("room-a", players);
            assertNotEquals(firstMatch, second.matchId());
            assertEquals(1, second.playerId("session-a").orElseThrow());
        }
    }

    @Test void readySetsStateIdempotentlyAndChecksMembership() throws Exception {
        var rooms = new RoomManager(IntegrationContract.MAX_PLAYERS);
        try (var socket = new Socket(); var otherSocket = new Socket()) {
            var host = new Session("host", socket);
            var room = rooms.createRoom("contract", host);
            assertEquals(SessionState.READY, rooms.setReady(host, true).orElseThrow());
            assertEquals(SessionState.READY, rooms.setReady(host, true).orElseThrow());
            assertEquals(SessionState.IN_ROOM, rooms.setReady(host, false).orElseThrow());
            assertEquals(SessionState.IN_ROOM, rooms.setReady(host, false).orElseThrow());
            var outsider = new Session("outsider", otherSocket);
            outsider.setRoomId(room.getRoomId());
            outsider.setState(SessionState.IN_ROOM);
            assertTrue(rooms.setReady(outsider, true).isEmpty());
            assertEquals(SessionState.IN_ROOM, outsider.getState());
        }
    }

    @Test void malformedOrWrongScopeAndOversizedFramesAreRejected() throws Exception {
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.wrap(MATCH, new Packet(PacketType.PING, "{}")));
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.wrap(MATCH,
                new Packet(PacketType.MOVE, "{\"matchId\":\"other\"}")));
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.unwrap(
                new Packet(PacketType.MOVE, "{\"matchId\":\"" + MATCH + "\"x}"), MATCH));
        var tooLarge = new Packet(PacketType.MOVE, "{\"padding\":\"" + "x".repeat(PacketEncoder.MAX_PACKET_LENGTH) + "\"}");
        assertThrows(ProtocolException.class, () -> MatchEnvelopeCodec.wrap(MATCH, tooLarge));
        assertEquals(new Packet(PacketType.REQUEST_FULL_STATE, "{}"), MatchEnvelopeCodec.unwrap(
                MatchEnvelopeCodec.wrap(MATCH, new Packet(PacketType.REQUEST_FULL_STATE, "{}")), MATCH));
    }

    private static GameStateUpdate full() {
        return GameStateUpdate.fullStateModel(1, 1, 1, 32, GameState.MatchState.RUNNING,
                List.of(TileType.EMPTY), List.of());
    }
}

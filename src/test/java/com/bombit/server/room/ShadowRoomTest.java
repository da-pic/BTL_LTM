package com.bombit.server.room;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.ProtocolRejection;
import com.bombit.server.game.mode.ModeRegistry;
import com.bombit.server.game.mode.shadow.*;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;
import java.net.Socket;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShadowRoomTest {
    private static Session peer(String name) {
        var peer = new Session(name, new Socket());peer.completeHandshake(name);return peer;
    }

    @Test void membershipChangesInvalidateShadowReadyButThemeAndFailedJoinsDoNot() {
        var rooms = new RoomManager(4);
        var host = peer("host");var a = peer("a");var b = peer("b");var c = peer("c");var extra = peer("extra");
        try {
            assertThrows(IllegalArgumentException.class, () -> rooms.createRoom("bad", host, 2, ShadowMatchSetup.newConfiguration()));
            var room = rooms.createRoom("shadow", host, 4, ShadowMatchSetup.newConfiguration());
            assertEquals(1, room.configurationRevision());
            assertTrue(rooms.joinRoom(room.getRoomId(), a));long old = room.configurationRevision();
            assertTrue(room.setReady(a, true, old));assertFalse(room.canStartGame());
            assertTrue(rooms.joinRoom(room.getRoomId(), b));assertEquals(old + 1, room.configurationRevision());
            assertEquals(SessionState.IN_ROOM, a.getState());assertNull(room.readyRevision(a.getPlayerId()));
            assertEquals("CONFIGURATION_STALE", assertThrows(ProtocolRejection.class, () -> room.setReady(a, true, old)).code());
            assertTrue(rooms.joinRoom(room.getRoomId(), c));
            long revision = room.configurationRevision();
            for (var guest : List.of(a, b, c)) assertTrue(room.setReady(guest, true, revision));
            assertTrue(room.canStartGame());
            assertFalse(room.addPlayer(extra));assertFalse(room.addPlayer(a));assertFalse(room.removePlayer("missing"));
            assertEquals(revision, room.configurationRevision());assertTrue(room.canStartGame());
            room.setMapSelection(MapSelection.ICE);assertEquals(revision, room.configurationRevision());assertTrue(room.canStartGame());
            var frozen = ShadowMatchSetup.prepare(RoomManager.viewV3(room));
            rooms.leaveRoom(a);
            assertEquals(revision + 1, room.configurationRevision());assertFalse(room.canStartGame());
            assertEquals(SessionState.IN_ROOM, b.getState());assertEquals(SessionState.IN_ROOM, c.getState());
            assertEquals(4, frozen.roster().players().size());
            assertEquals(TeamId.B, frozen.roster().teamOf(4));
            assertEquals(c.getPlayerId(), frozen.roster().player(4).sessionId());
            assertTrue(rooms.joinRoom(room.getRoomId(), extra));
            for (var guest : List.of(b, c, extra)) room.setReady(guest, true, room.configurationRevision());
            assertTrue(room.canStartGame());
            var next = ShadowMatchSetup.prepare(RoomManager.viewV3(room));
            assertEquals(b.getPlayerId(), next.roster().player(2).sessionId());
            assertEquals(extra.getPlayerId(), next.roster().player(4).sessionId());
            // Roster mới chỉ áp cho lượt sau; roster đã chốt không đổi theo danh sách Room.
            assertEquals(a.getPlayerId(), frozen.roster().player(2).sessionId());
        } finally { for (var s : List.of(host, a, b, c, extra)) s.closeSocket(); }
    }

    @Test void switchingToShadowForcesFourSlotsAndSwitchingBackKeepsCapacity() {
        var host = peer("host");var guest = peer("guest");var rooms = new RoomManager(4);
        try {
            var classic = new ModeRegistry().resolvePublic(GameMode.CLASSIC);
            var room = rooms.createRoom("room", host, 2, classic);rooms.joinRoom(room.getRoomId(), guest);
            room.setReady(guest, true, 1);assertTrue(room.canStartGame());
            assertTrue(room.changeConfiguration(ShadowMatchSetup.newConfiguration()));
            assertEquals(4, room.getMaxPlayers());assertEquals(2, room.configurationRevision());assertFalse(room.canStartGame());
            assertEquals(SessionState.IN_ROOM, guest.getState());
            assertFalse(room.changeConfiguration(ShadowMatchSetup.newConfiguration()));assertEquals(2, room.configurationRevision());
            assertTrue(room.changeConfiguration(classic));assertEquals(4, room.getMaxPlayers());
            room.setReady(guest, true, room.configurationRevision());assertTrue(room.canStartGame());
        } finally {host.closeSocket();guest.closeSocket();}
    }
}

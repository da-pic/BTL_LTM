package com.bombit.server.room;

import org.junit.jupiter.api.Test;
import com.bombit.server.session.Session;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class RoomManagerTest {
    @Test
    void createsJoinsAndReturnsSafeSnapshot() {
        RoomManager manager = new RoomManager(4);
        Session host = new Session("host", new Socket());
        host.setState(com.bombit.server.session.SessionState.IN_LOBBY);
        Room room = manager.createRoom("test", host);

        Session guest = new Session("guest", new Socket());
        guest.setState(com.bombit.server.session.SessionState.IN_LOBBY);
        assertTrue(manager.joinRoom(room.getRoomId(), guest));
        assertEquals(2, room.getPlayersSnapshot().size());
        assertThrows(UnsupportedOperationException.class,
                () -> room.getPlayersSnapshot().clear());
        host.closeSocket();
        guest.closeSocket();
    }
}

package com.bombit.server.room;

import org.junit.jupiter.api.Test;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionState;

import java.net.Socket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoomManagerTest {

    @Test
    void createsJoinsAndReturnsSafeSnapshot() {
        RoomManager manager = new RoomManager(4);
        Session host = new Session("host", new Socket());
        host.setState(SessionState.IN_LOBBY);
        Room room = manager.createRoom("test", host);

        Session guest = new Session("guest", new Socket());
        guest.setState(SessionState.IN_LOBBY);
        assertTrue(manager.joinRoom(room.getRoomId(), guest));
        assertEquals(2, room.getPlayersSnapshot().size());

        List<Session> snapshot = room.getPlayersSnapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
        assertEquals(2, room.getPlayersSnapshot().size());

        host.closeSocket();
        guest.closeSocket();
    }

    @Test
    void rejectsWhenRoomIsFull() {
        RoomManager manager = new RoomManager(2); // Max 2 players
        Session host = new Session("host", new Socket());
        host.setState(SessionState.IN_LOBBY);
        Room room = manager.createRoom("test", host);

        Session guest1 = new Session("guest1", new Socket());
        guest1.setState(SessionState.IN_LOBBY);
        assertTrue(manager.joinRoom(room.getRoomId(), guest1));
        assertTrue(room.isFull());

        Session guest2 = new Session("guest2", new Socket());
        guest2.setState(SessionState.IN_LOBBY);
        // Third player must be rejected because room is full
        assertFalse(manager.joinRoom(room.getRoomId(), guest2));
        assertEquals(2, room.getPlayersSnapshot().size());

        host.closeSocket();
        guest1.closeSocket();
        guest2.closeSocket();
    }

    @Test
    void rejectsWhenPlayerJoinsTwiceOrAlreadyInRoom() {
        RoomManager manager = new RoomManager(4);
        Session host = new Session("host", new Socket());
        host.setState(SessionState.IN_LOBBY);
        Room room1 = manager.createRoom("room1", host);

        Session guest = new Session("guest", new Socket());
        guest.setState(SessionState.IN_LOBBY);
        assertTrue(manager.joinRoom(room1.getRoomId(), guest));

        // Guest attempts to join room1 a second time
        assertFalse(manager.joinRoom(room1.getRoomId(), guest));

        // Create a second room
        Session host2 = new Session("host2", new Socket());
        host2.setState(SessionState.IN_LOBBY);
        Room room2 = manager.createRoom("room2", host2);

        // Guest (already in room1) attempts to join room2
        assertFalse(manager.joinRoom(room2.getRoomId(), guest));
        assertEquals(1, room2.getPlayersSnapshot().size());

        host.closeSocket();
        guest.closeSocket();
        host2.closeSocket();
    }

    @Test
    void transfersHostWhenHostLeaves() {
        RoomManager manager = new RoomManager(4);
        Session host = new Session("host", new Socket());
        host.setState(SessionState.IN_LOBBY);
        Room room = manager.createRoom("test", host);

        Session guest = new Session("guest", new Socket());
        guest.setState(SessionState.IN_LOBBY);
        manager.joinRoom(room.getRoomId(), guest);

        assertEquals(host.getPlayerId(), room.getHostPlayerId());

        // Host leaves the room
        manager.leaveRoom(host);

        // Guest becomes the new host
        assertEquals(guest.getPlayerId(), room.getHostPlayerId());
        assertEquals(1, room.getPlayersSnapshot().size());

        // Guest leaves -> room becomes empty and is removed from manager
        manager.leaveRoom(guest);
        assertTrue(room.isEmpty());
        assertNull(manager.find(room.getRoomId()));

        host.closeSocket();
        guest.closeSocket();
    }

    @Test
    void readyToggleAndStartGame() {
        RoomManager manager = new RoomManager(4);
        Session host = new Session("host", new Socket());
        host.setState(SessionState.IN_LOBBY);
        Room room = manager.createRoom("test", host);

        Session guest = new Session("guest", new Socket());
        guest.setState(SessionState.IN_LOBBY);
        manager.joinRoom(room.getRoomId(), guest);

        // Cannot start when guest is not ready
        assertFalse(room.canStartGame());
        assertFalse(manager.startGame(host).isPresent());

        // Toggle ready for guest
        Optional<SessionState> state = manager.toggleReady(guest);
        assertTrue(state.isPresent());
        assertEquals(SessionState.READY, state.get());
        assertTrue(room.canStartGame());

        // Non-host cannot start game
        assertFalse(manager.startGame(guest).isPresent());

        // Host starts game
        Optional<Room> started = manager.startGame(host);
        assertTrue(started.isPresent());
        assertEquals(Room.State.PLAYING, room.getState());
        assertEquals(SessionState.PLAYING, host.getState());
        assertEquals(SessionState.PLAYING, guest.getState());

        host.closeSocket();
        guest.closeSocket();
    }
}

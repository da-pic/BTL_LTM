# Mai network foundation

Maven project containing the TV1 server-side network/session foundation for
Bomb IT Multiplayer (team 3). The other members merge their modules into this
project following the integration guide below.

The project targets Java 25, the current LTS release.

## Implemented

- TCP server lifecycle, graceful shutdown and bounded client thread pool.
- Session state machine, session registry and reconnect token auth.
- ConnectionManager: per-session outbound writer lifecycle (released on drop).
- Dynamic rooms with safe player snapshots, ready toggle and start-game flow.
- Room broadcasts: `ROOM_STATE` (0x0012) and `GAME_STARTED` (0x0015), JSON payloads.
- RoomCleanupSink: hard drop removes the player from the room, releases the
  writer and broadcasts `PLAYER_DISCONNECTED` (0x0032) to the room.
- Room-scoped chat queue with 100-character validation.
- Client heartbeat tracking, timeout sweep and `PONG` reply (0x0041).
- Soft disconnect and reconnect window with previous-state restore.

Message type codes are temporary and follow Giang's protocol table; payloads
are JSON UTF-8 (contract not final).

The project deliberately does not implement game rules or packet encoding. Those
parts belong to the other team modules and can be connected through the public
interfaces in `server.integration`.

## Requirements

- JDK 25+ (`maven.compiler.release=25`, tested on Temurin 25.0.4).
- Maven 3.9+ (tested on 3.10.0).

## Run and test

### Build and run the unit tests

```text
mvn test
```

### Start the server

```text
mvn package
java -cp target/classes server.network.Server          # default port 8888
java -cp target/classes server.network.Server 9999     # custom port
```

Expected startup log line:

```text
Bomb IT server listening on port 8888
```

The same entry point works from the packaged jar:

```text
java -cp target/mai-network-foundation-0.1.0-SNAPSHOT.jar server.network.Server
```

### Quick connectivity check

```powershell
Test-NetConnection -ComputerName localhost -Port 8888
```

> Heartbeat note: a client that sends no bytes for 10 seconds is soft-dropped by
> the heartbeat sweep (runs every 2 seconds). Manual test tools must keep sending
> data, or send `PING` once Giang's router is wired.

## Integration guide for teammates

### Package ownership

| Member | Packages | Scope |
| --- | --- | --- |
| Mai (TV1) | `server.network`, `server.session`, `server.room`, `server.chat`, `server.heartbeat`, `server.integration` | TCP, session, room, chat, heartbeat (done) |
| Giang (TV4) | `common.protocol`, `common.router`, `client.network`, `metrics`, `testing` | packet encoder/decoder, router, metrics |
| Duy (TV2) | `server.game`, `server.movement`, `client.movement` | game state, game loop, movement, state sync |
| Chinh (TV3) | `server.bomb`, `server.item` | bomb, explosion, item, damage |

Rule: only edit files inside your own packages. Cross-module calls go through
the public APIs below; do not reach into internal fields of another package.

### Hooks for Giang (protocol and router)

- `ClientHandler.run()` currently reads raw bytes; the connection point is
  marked with a comment in
  `src/main/java/server/network/ClientHandler.java`.
- Handlers the router should call after decoding a packet:
  - `RoomManager.createRoom(roomName, session)`, `joinRoom(roomId, session)`,
    `leaveRoom(session)`
  - `RoomManager.toggleReady(session)` and `RoomManager.startGame(session)`
    (return `Optional`; only the host can start)
  - `ChatHandler.enqueue(new ChatMessage(roomId, senderName, message))`
  - `HeartbeatManager.onPing(session, payload)` — the server replies `PONG`
    (0x0041) and echoes the payload so the client can measure RTT.
- Sending packets:
  - Per session: `session.getOutboundSender().send(session, msg)` or reuse
    `BroadcastSender` (safely skips closed sessions and full queues).
  - Room broadcast: `SessionManager.broadcastToRoom(roomId, msg)`.
  - Packet object: `OutboundMessage(short type, byte[] payload)`.
- On-wire frame written by `SessionWriter`:
  `LENGTH(int32) | TYPE(int16) | PAYLOAD`, big-endian,
  `LENGTH` = number of bytes of `TYPE + PAYLOAD` (matches Giang's plan).
- The composition root `Server` exposes getters: `getSessionManager()`,
  `getRoomManager()`, `getConnectionManager()`, `getChatHandler()`,
  `getHeartbeatManager()`.

### Hooks for Duy (game layer)

- Implement / register a `GameEventSink` to receive soft drop, hard drop and
  reconnect events. `Server` currently wires `RoomCleanupSink`; when the game
  sink is ready, compose both (RoomCleanupSink + game sink) or extend
  `RoomCleanupSink` with a delegate.
- Rooms expose an immutable `getPlayersSnapshot()` and `Room.State`
  (`WAITING` / `PLAYING`). After a successful `startGame`, every member is in
  `SessionState.PLAYING`.
- During soft drop the seat is kept and the character should stay frozen; hard
  drop removes the player from the room automatically.

### Hooks for Chinh (bomb and item)

- Broadcast gameplay events to the correct room with
  `SessionManager.broadcastToRoom(roomId, new OutboundMessage(type, payload))`.
- Player presence: `Session.getState()` — `DISCONNECTED` = inside the reconnect
  window (frozen), `CLOSED` = hard-dropped and removed.

### Temporary message codes used here

| Code | Message | Constant location |
| --- | --- | --- |
| 0x0012 | `ROOM_STATE` | `RoomManager.ROOM_STATE_TYPE` |
| 0x0015 | `GAME_STARTED` | `RoomManager.GAME_STARTED_TYPE` |
| 0x0032 | `PLAYER_DISCONNECTED` | `RoomCleanupSink.PLAYER_DISCONNECTED_TYPE` |
| 0x0041 | `PONG` | `HeartbeatManager.PONG_TYPE` |
| 0x0050 | `CHAT` | `ChatHandler.CHAT_MESSAGE_TYPE` |

These follow Giang's protocol table. When the official table is locked, only
the constants change; the structure stays the same.

### Merge workflow

1. Copy your packages into `src/main/java/...` keeping the agreed package names;
   do not modify files owned by other members.
2. `mvn test` must be green before merging.
3. Smoke run: `mvn package`, start `server.network.Server`, connect a client.
4. Do not commit `target/` or `.idea/` (already in `.gitignore`).

### End-to-end checklist (after the router is wired)

1. Server starts and logs the listening line.
2. Client sends `CONNECT` and receives `CONNECT_ACK`.
3. Create / join a room; members receive `ROOM_STATE`.
4. `READY` + `START_GAME`; members receive `GAME_STARTED`.
5. Client drops mid-game → 15 second reconnect window; after the window the
   remaining players receive `PLAYER_DISCONNECTED`.

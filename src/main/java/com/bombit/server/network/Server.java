package com.bombit.server.network;

import com.bombit.server.chat.ChatHandler;
import com.bombit.server.heartbeat.HeartbeatManager;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.integration.RoomCleanupSink;
import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class Server implements AutoCloseable {
    public static final int DEFAULT_PORT = 8888;
    public static final int HEARTBEAT_TIMEOUT_SECONDS = 10;
    public static final int RECONNECT_GRACE_SECONDS = 15;
    public static final int MAX_PLAYERS_PER_ROOM = 4;
    private static final int CLIENT_POOL_SIZE = 32;
    private static final int OUTBOUND_QUEUE_CAPACITY = 256;

    private final ServerSocket serverSocket;
    private final ExecutorService clientPool = Executors.newFixedThreadPool(CLIENT_POOL_SIZE);
    private final ConnectionManager connectionManager = new ConnectionManager();
    private final RoomManager roomManager = new RoomManager(MAX_PLAYERS_PER_ROOM);
    private final SessionManager sessionManager;
    private final ChatHandler chatHandler;
    private final HeartbeatManager heartbeatManager;
    private volatile boolean running;

    public Server(int port) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        this.serverSocket = new ServerSocket(port);
        BroadcastSender broadcastSender = new BroadcastSender();
        GameEventSink gameEventSink = new RoomCleanupSink(roomManager, connectionManager, broadcastSender);
        this.sessionManager = new SessionManager(gameEventSink, RECONNECT_GRACE_SECONDS);
        this.roomManager.setBroadcaster(sessionManager::broadcastToRoom);
        this.chatHandler = new ChatHandler(roomManager, broadcastSender, OUTBOUND_QUEUE_CAPACITY);
        this.heartbeatManager = new HeartbeatManager(
                sessionManager, HEARTBEAT_TIMEOUT_SECONDS, TimeUnit.SECONDS, broadcastSender);
    }

    public void start() throws IOException {
        running = true;
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException closed) {
                if (!running) {
                    break;
                }
                throw closed;
            }
            Session session = new Session("pending-" + socket.getRemoteSocketAddress(), socket);
            sessionManager.register(session);
            connectionManager.open(session);
            try {
                clientPool.execute(new ClientHandler(socket, session, sessionManager));
            } catch (RejectedExecutionException rejected) {
                sessionManager.hardDrop(session.getPlayerId());
            }
        }
    }

    public int getPort() { return serverSocket.getLocalPort(); }
    public SessionManager getSessionManager() { return sessionManager; }
    public RoomManager getRoomManager() { return roomManager; }
    public ConnectionManager getConnectionManager() { return connectionManager; }
    public ChatHandler getChatHandler() { return chatHandler; }
    public HeartbeatManager getHeartbeatManager() { return heartbeatManager; }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing an already closed server socket is harmless.
        }
        clientPool.shutdownNow();
        try {
            clientPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        chatHandler.close();
        heartbeatManager.close();
        sessionManager.close();
        connectionManager.close();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        try (Server server = new Server(port)) {
            System.out.println("Bomb IT server listening on port " + server.getPort());
            server.start();
        }
    }
}

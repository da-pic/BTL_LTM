package com.bombit.server.network;

import com.bombit.server.chat.ChatHandler;
import com.bombit.server.heartbeat.HeartbeatManager;
import com.bombit.server.integration.GameEventSink;
import com.bombit.server.integration.RoomCleanupSink;
import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;
import com.bombit.server.session.SessionState;
import com.bombit.server.dispatch.MessageRouter;
import com.bombit.server.dispatch.SessionHandlers;
import com.bombit.common.protocol.IntegrationContract;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<Throwable> connectionFailure = new AtomicReference<>();
    private final ExecutorService clientPool = new ThreadPoolExecutor(CLIENT_POOL_SIZE, CLIENT_POOL_SIZE,
            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CLIENT_POOL_SIZE), task -> {
                Thread worker = new Thread(task, "server-client-reader");
                worker.setUncaughtExceptionHandler((thread, error) -> connectionFailure.compareAndSet(null, error));
                return worker;
            });
    private final ConnectionManager connectionManager;
    private final RoomManager roomManager = new RoomManager(MAX_PLAYERS_PER_ROOM);
    private final SessionManager sessionManager;
    private final ChatHandler chatHandler;
    private final HeartbeatManager heartbeatManager;
    private volatile boolean running;
    private final MessageRouter router = new MessageRouter(message -> { });
    private final Object lifecycle = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final int frameTimeoutMillis;
    private volatile java.util.function.Consumer<Session> connected = session -> { };
    private volatile GameEventSink gameObserver = new com.bombit.server.integration.NoOpGameEventSink();

    public Server(int port) throws IOException {
        this(port, IntegrationContract.HEARTBEAT_TIMEOUT_MILLIS);
    }

    /** Shorter frame deadline is useful for deterministic transport tests. */
    public Server(int port, int frameTimeoutMillis) throws IOException {
        this(port,frameTimeoutMillis,TimeUnit.SECONDS.toMillis(HEARTBEAT_TIMEOUT_SECONDS),2000,5000);
    }
    /** Injectable time bounds keep fault tests short without changing application defaults. */
    public Server(int port,int frameTimeoutMillis,long heartbeatMillis,long sweepMillis,long writeMillis)throws IOException{
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        if (frameTimeoutMillis <= 0) throw new IllegalArgumentException("Invalid frame timeout");
        if(heartbeatMillis<=0||sweepMillis<=0||writeMillis<=0)throw new IllegalArgumentException("Invalid deadlines");
        this.frameTimeoutMillis = frameTimeoutMillis;
        this.serverSocket = new ServerSocket(port);
        this.connectionManager=new ConnectionManager(OUTBOUND_QUEUE_CAPACITY,writeMillis);
        BroadcastSender broadcastSender = new BroadcastSender();
        GameEventSink roomCleanup = new RoomCleanupSink(roomManager, connectionManager, broadcastSender);
        GameEventSink gameEventSink = new GameEventSink() {
            public void onPlayerDisconnected(Session session) {
                try { gameObserver.onPlayerDisconnected(session); } finally { roomCleanup.onPlayerDisconnected(session); }
            }
            public void onPlayerReconnected(Session session) {
                try { gameObserver.onPlayerReconnected(session); } finally { roomCleanup.onPlayerReconnected(session); }
            }
            public void onPlayerHardDropped(Session session) {
                try { gameObserver.onPlayerHardDropped(session); } finally { roomCleanup.onPlayerHardDropped(session); }
            }
        };
        this.sessionManager = new SessionManager(gameEventSink, RECONNECT_GRACE_SECONDS);
        connectionManager.setFailureHandler((session, socket) -> {
            if (session.getSocket() == socket) {
                if (session.getState() == SessionState.CONNECTED) sessionManager.hardDrop(session.getPlayerId());
                else sessionManager.handleUnexpectedDisconnect(session.getPlayerId());
            }
        });
        SessionHandlers.register(router, sessionManager, broadcastSender, session -> connected.accept(session));
        this.roomManager.setBroadcaster(sessionManager::broadcastToRoom);
        this.chatHandler = new ChatHandler(roomManager, broadcastSender, OUTBOUND_QUEUE_CAPACITY);
        this.heartbeatManager = new HeartbeatManager(
                sessionManager,heartbeatMillis,TimeUnit.MILLISECONDS,broadcastSender,sweepMillis);
    }

    public void start() throws IOException {
        synchronized (lifecycle) {
            if (closed.get() || !started.compareAndSet(false, true)) throw new IllegalStateException("Server starts once");
            running = true;
        }
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
            synchronized (lifecycle) {
                if (!running) { socket.close(); break; }
                // Includes grace-period sessions: reconnect churn cannot grow retained sessions without bound.
                if(sessionManager.snapshot().size()>=256){socket.close();continue;}
                socket.setTcpNoDelay(true);
                Session session = new Session("pending-" + socket.getRemoteSocketAddress(), socket);
                sessionManager.register(session);
                SessionWriter writer = connectionManager.open(session);
                try {
                    clientPool.execute(new ClientHandler(socket, session, sessionManager,
                            connectionManager, router, writer, frameTimeoutMillis,e->connectionFailure.compareAndSet(null,e)));
                } catch (RejectedExecutionException rejected) {
                    sessionManager.hardDrop(session.getPlayerId());
                }
            }
        }
    }

    public int getPort() { return serverSocket.getLocalPort(); }
    public SessionManager getSessionManager() { return sessionManager; }
    public RoomManager getRoomManager() { return roomManager; }
    public ConnectionManager getConnectionManager() { return connectionManager; }
    public ChatHandler getChatHandler() { return chatHandler; }
    public HeartbeatManager getHeartbeatManager() { return heartbeatManager; }
    public MessageRouter getRouter() { return router; }
    /** Composition hooks: install before start; transport-only Server remains usable unchanged. */
    public void setConnectedHandler(java.util.function.Consumer<Session> callback) { connected = java.util.Objects.requireNonNull(callback); }
    public void setGameObserver(GameEventSink observer) { gameObserver = java.util.Objects.requireNonNull(observer); }
    public java.util.Optional<Throwable> getConnectionFailure() { return java.util.Optional.ofNullable(connectionFailure.get()); }
    public boolean isTerminated(){return clientPool.isTerminated()&&connectionManager.isTerminated()
            &&sessionManager.isTerminated()&&heartbeatManager.isTerminated()&&!chatHandler.isAlive();}

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            running = false;
            try { serverSocket.close(); } catch (IOException ignored) { }
        }
        heartbeatManager.close();
        // Close sockets before awaiting readers blocked in decode/readFully.
        sessionManager.close();
        connectionManager.close();
        clientPool.shutdown();
        try {
            // Socket close wakes decode. Let lifecycle callbacks finish before interrupting readers.
            if (!clientPool.awaitTermination(2, TimeUnit.SECONDS)) {
                clientPool.shutdownNow();
                clientPool.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            clientPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        chatHandler.close();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        try (Server server = new Server(port)) {
            System.out.println("Bomb IT server listening on port " + server.getPort());
            server.start();
        }
    }
}

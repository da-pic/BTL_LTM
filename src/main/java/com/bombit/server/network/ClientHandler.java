package com.bombit.server.network;

import com.bombit.common.protocol.*;
import com.bombit.server.dispatch.MessageRouter;
import com.bombit.server.dispatch.SessionHandlers;
import com.bombit.server.dispatch.RequestRejectedException;
import com.bombit.server.session.*;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Decode complete frames, gate handshake, then route using the server-owned session ID. */
public final class ClientHandler implements Runnable {
    private final Socket socket;
    private volatile Session session;
    private final SessionManager sessions;
    private final ConnectionManager connections;
    private final MessageRouter router;
    private volatile SessionWriter writer;
    private final int frameTimeoutMillis;
    private final InboundRateLimiter limits=new InboundRateLimiter();
    private final java.util.function.Consumer<Throwable> failures;
    private final boolean currentProtocol;

    public ClientHandler(Socket socket, Session session, SessionManager sessions,
                         ConnectionManager connections, MessageRouter router, SessionWriter writer,
                         int frameTimeoutMillis) {
        this(socket,session,sessions,connections,router,writer,frameTimeoutMillis,e->{});
    }
    public ClientHandler(Socket socket,Session session,SessionManager sessions,ConnectionManager connections,
                         MessageRouter router,SessionWriter writer,int frameTimeoutMillis,java.util.function.Consumer<Throwable> failures){
        this(socket,session,sessions,connections,router,writer,frameTimeoutMillis,failures,false);
    }
    public ClientHandler(Socket socket,Session session,SessionManager sessions,ConnectionManager connections,
                         MessageRouter router,SessionWriter writer,int frameTimeoutMillis,java.util.function.Consumer<Throwable> failures,boolean currentProtocol){
        this.socket=socket; this.session=session; this.sessions=sessions; this.connections=connections;
        this.router=router; this.writer=writer; this.frameTimeoutMillis=frameTimeoutMillis;
        this.failures=failures;
        this.currentProtocol=currentProtocol;
    }
    @Override public void run() {
        boolean hardDrop = false, drain = false;
        try {
            while (session.getSocket() == socket && !socket.isClosed()) {
                Packet packet = readFrame();
                if (session.getSocket() != socket) break;
                var limit=limits.check(packet.getType(),System.nanoTime());
                if(limit!=InboundRateLimiter.Decision.ALLOW){
                    error("RATE_LIMITED");
                    if(limit==InboundRateLimiter.Decision.CLOSE){hardDrop=true;drain=true;break;}
                    continue;
                }
                boolean pending = session.getState() == SessionState.CONNECTED;
                if (pending && packet.getType() != PacketType.CONNECT && packet.getType() != PacketType.RECONNECT) {
                    error("NOT_CONNECTED"); hardDrop=true; drain=true; break;
                }
                if (!pending && (packet.getType() == PacketType.CONNECT || packet.getType() == PacketType.RECONNECT)) {
                    error("INVALID_PACKET"); continue;
                }
                if (pending && packet.getType() == PacketType.RECONNECT) {
                    SessionPacketCodec.Reconnect req;
                    try {
                        if(currentProtocol){var v3=SessionV3Codec.reconnect(packet);req=new SessionPacketCodec.Reconnect(v3.playerId(),v3.reconnectToken());}
                        else req = SessionPacketCodec.reconnect(packet);
                    } catch (ProtocolRejection rejected){
                        error(rejected.code());hardDrop=true;drain=true;break;
                    } catch (RuntimeException invalid) {
                        error("INVALID_PACKET"); hardDrop=true; drain=true; break;
                    }
                    // Preflight giữ socket tạm sống để trả ERROR cho token sai/hết hạn.
                    // reconnect() vẫn kiểm tra nguyên tử lần nữa, không tin kết quả preflight.
                    if (!sessions.canReconnect(req.playerId(), req.reconnectToken())) {
                        error(currentProtocol?"RECOVERY_REJECTED":"RECONNECT_REJECTED"); hardDrop=true; drain=true; break;
                    }
                    String provisionalId = session.getPlayerId();
                    boolean ok = sessions.reconnect(req.playerId(), req.reconnectToken(), socket, resumed -> {
                        // Chỉ bỏ phiên tạm SAU khi SessionManager đã giữ quyền reconnect.
                        // Nếu token/deadline sai, writer tạm vẫn gửi được ERROR cho client.
                        connections.releasePreservingSocket(provisionalId);
                        if (!sessions.discardProvisional(provisionalId))
                            throw new IllegalStateException("Provisional session no longer owned");
                        var w = connections.open(resumed);
                        this.session = resumed;
                        this.writer = w;
                        w.send(resumed, OutboundMessage.fromPacket(
                                currentProtocol?SessionV3Codec.acknowledgement(resumed.getPlayerId(),resumed.getReconnectToken(),resumed.connectionId()):
                                SessionPacketCodec.acknowledgement(resumed.getPlayerId(), resumed.getReconnectToken())));
                    });
                    if (!ok) {
                        error(currentProtocol?"RECOVERY_REJECTED":"RECONNECT_REJECTED"); hardDrop=true; drain=true; break;
                    }
                    continue;
                }
                try {
                    if (!router.route(session.getPlayerId(), packet)) { error("UNKNOWN_MESSAGE"); continue; }
                    if (packet.getType() != PacketType.CONNECT) session.updateHeartbeat();
                    if (packet.getType() == PacketType.DISCONNECT) { hardDrop=true; drain=true; break; }
                } catch (SessionHandlers.UnsupportedVersionException unsupported) {
                    error("UNSUPPORTED_VERSION"); hardDrop=true; drain=true; break;
                } catch (ProtocolRejection rejected){
                    error(rejected.code());if(pending){hardDrop=true;drain=true;break;}
                } catch (RequestRejectedException rejected) {
                    error(rejected.code());
                } catch (IllegalArgumentException invalid) {
                    error("INVALID_PACKET");
                    if (pending) { hardDrop=true; drain=true; break; }
                } catch (RuntimeException failed) {
                    failures.accept(failed);error("SERVER_ERROR");hardDrop=true;drain=true;break;
                }
            }
        } catch (ProtocolException invalidFrame) {
            hardDrop = true; // Invalid length/UTF-8: do not attempt to recover frame boundaries.
        } catch (IOException disconnected) {
            // EOF, truncated frame and timeouts all end this captured connection.
        } finally {
            if (drain) writer.finishAndAwait();
            if (session.getSocket() == socket) {
                if (hardDrop || session.getState() == SessionState.CONNECTED) sessions.hardDrop(session.getPlayerId(), socket);
                else sessions.handleUnexpectedDisconnect(session.getPlayerId(), socket);
            }
            connections.release(session.getPlayerId(), socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
    private void error(String code) {
        try { writer.send(session, OutboundMessage.fromPacket(currentProtocol?SessionV3Codec.error(code):SessionPacketCodec.error(code))); }
        catch (IllegalStateException closedWriter) { /* Writer failure already closes and cleans this connection. */ }
    }
    private Packet readFrame() throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(frameTimeoutMillis);
        InputStream input = new FilterInputStream(socket.getInputStream()) {
            private void remaining() throws IOException {
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) throw new SocketTimeoutException("Frame deadline exceeded");
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
            }
            @Override public int read() throws IOException { remaining(); return in.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { remaining(); return in.read(b, off, len); }
        };
        return PacketDecoder.decode(input);
    }
}

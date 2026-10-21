package com.bombit.server.network;

import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.PacketDecoder;
import com.bombit.common.protocol.ProtocolException;
import com.bombit.server.dispatch.MessageRouter;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;

public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final Session session;
    private final SessionManager sessionManager;
    private final MessageRouter messageRouter;

    public ClientHandler(Socket socket, Session session, SessionManager sessionManager, MessageRouter messageRouter) {
        this.socket = socket;
        this.session = session;
        this.sessionManager = sessionManager;
        this.messageRouter = messageRouter;
    }

    public ClientHandler(Socket socket, Session session, SessionManager sessionManager) {
        this(socket, session, sessionManager, null);
    }

    @Override
    public void run() {
        try (socket; InputStream input = socket.getInputStream()) {
            while (!socket.isClosed()) {
                Packet packet;
                try {
                    packet = PacketDecoder.decode(input);
                } catch (EOFException eof) {
                    // Client closed stream cleanly.
                    break;
                } catch (ProtocolException protocolError) {
                    // Protocol violation: isolate and break without crashing server.
                    System.err.println("[ClientHandler] Gói tin lỗi từ " + session.getPlayerId() + ": " + protocolError.getMessage());
                    break;
                }
                session.updateHeartbeat();
                if (messageRouter != null) {
                    messageRouter.route(session.getPlayerId(), packet);
                }
            }
        } catch (SocketException closedOrReset) {
            // Socket closed cleanly by server shutdown or connection reset by peer.
        } catch (IOException error) {
            // General I/O error on this client's socket: isolated to this client.
        } finally {
            if (session.getSocket() == socket) {
                sessionManager.handleUnexpectedDisconnect(session.getPlayerId());
            }
        }
    }
}

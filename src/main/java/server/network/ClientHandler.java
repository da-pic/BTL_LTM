package server.network;

import server.session.Session;
import server.session.SessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final Session session;
    private final SessionManager sessionManager;

    public ClientHandler(Socket socket, Session session, SessionManager sessionManager) {
        this.socket = socket;
        this.session = session;
        this.sessionManager = sessionManager;
    }

    @Override
    public void run() {
        try (socket; InputStream input = socket.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    // Interim: any received bytes count as liveness until the router parses PING.
                    session.updateHeartbeat();
                }
                // PacketDecoder/MessageRouter will be connected here by Giang.
            }
        } catch (IOException error) {
            // Sockets closed by reconnect or cleanup also end up here.
        } finally {
            if (session.getSocket() == socket) {
                sessionManager.handleUnexpectedDisconnect(session.getPlayerId());
            }
        }
    }
}

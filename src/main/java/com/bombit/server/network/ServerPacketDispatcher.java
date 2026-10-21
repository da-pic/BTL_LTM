package com.bombit.server.network;

import com.bombit.common.protocol.Packet;
import com.bombit.common.protocol.PacketType;
import com.bombit.server.chat.ChatHandler;
import com.bombit.server.chat.ChatMessage;
import com.bombit.server.dispatch.MessageRouter;
import com.bombit.server.heartbeat.HeartbeatManager;
import com.bombit.server.room.Room;
import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import com.bombit.server.session.SessionManager;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerPacketDispatcher {
    private final SessionManager sessionManager;
    private final RoomManager roomManager;
    private final ChatHandler chatHandler;
    private final HeartbeatManager heartbeatManager;
    private final ClientSender clientSender;

    public ServerPacketDispatcher(SessionManager sessionManager,
                                  RoomManager roomManager,
                                  ChatHandler chatHandler,
                                  HeartbeatManager heartbeatManager,
                                  ClientSender clientSender) {
        this.sessionManager = sessionManager;
        this.roomManager = roomManager;
        this.chatHandler = chatHandler;
        this.heartbeatManager = heartbeatManager;
        this.clientSender = clientSender;
    }

    public void registerTo(MessageRouter router) {
        router.registerHandler(PacketType.CONNECT, this::handleConnect);
        router.registerHandler(PacketType.CREATE_ROOM, this::handleCreateRoom);
        router.registerHandler(PacketType.JOIN_ROOM, this::handleJoinRoom);
        router.registerHandler(PacketType.READY, this::handleReady);
        router.registerHandler(PacketType.START_GAME, this::handleStartGame);
        router.registerHandler(PacketType.LEAVE_ROOM, this::handleLeaveRoom);
        router.registerHandler(PacketType.CHAT, this::handleChat);
        router.registerHandler(PacketType.PING, this::handlePing);
        router.registerHandler(PacketType.DISCONNECT, this::handleDisconnect);
    }

    private void handleConnect(String clientId, Packet packet) {
        Optional<Session> optSession = sessionManager.find(clientId);
        if (optSession.isEmpty()) {
            return;
        }
        Session session = optSession.get();
        String playerName = extractJsonString(packet.getJsonPayload(), "playerName");
        if (playerName == null || playerName.isBlank()) {
            playerName = extractJsonString(packet.getJsonPayload(), "username");
        }
        if (playerName != null && !playerName.isBlank()) {
            session.setPlayerName(playerName);
        }
        String ackJson = "{\"playerId\":\"" + escapeJson(session.getPlayerId())
                + "\",\"playerName\":\"" + escapeJson(session.getPlayerName())
                + "\",\"reconnectToken\":\"" + escapeJson(session.getReconnectToken()) + "\"}";
        clientSender.sendToPlayer(clientId, new OutboundMessage(PacketType.CONNECT_ACK, ackJson.getBytes(StandardCharsets.UTF_8)));
    }

    private void handleCreateRoom(String clientId, Packet packet) {
        Optional<Session> optSession = sessionManager.find(clientId);
        if (optSession.isEmpty()) {
            return;
        }
        Session session = optSession.get();
        String roomName = extractJsonString(packet.getJsonPayload(), "roomName");
        if (roomName == null || roomName.isBlank()) {
            roomName = "Room-" + session.getPlayerName();
        }
        try {
            roomManager.createRoom(roomName, session);
        } catch (Exception e) {
            sendError(clientId, "CREATE_ROOM_FAILED", e.getMessage());
        }
    }

    private void handleJoinRoom(String clientId, Packet packet) {
        Optional<Session> optSession = sessionManager.find(clientId);
        if (optSession.isEmpty()) {
            return;
        }
        Session session = optSession.get();
        String roomId = extractJsonString(packet.getJsonPayload(), "roomId");
        if (roomId == null || !roomManager.joinRoom(roomId, session)) {
            sendError(clientId, "JOIN_ROOM_FAILED", "Không thể vào phòng (phòng đầy hoặc không tồn tại)");
        }
    }

    private void handleReady(String clientId, Packet packet) {
        sessionManager.find(clientId).ifPresent(roomManager::toggleReady);
    }

    private void handleStartGame(String clientId, Packet packet) {
        Optional<Session> optSession = sessionManager.find(clientId);
        if (optSession.isEmpty()) {
            return;
        }
        Session session = optSession.get();
        Optional<Room> started = roomManager.startGame(session);
        if (started.isEmpty()) {
            sendError(clientId, "START_GAME_FAILED", "Chưa đủ điều kiện bắt đầu trận đấu hoặc bạn không phải Host");
        }
    }

    private void handleLeaveRoom(String clientId, Packet packet) {
        sessionManager.find(clientId).ifPresent(roomManager::leaveRoom);
    }

    private void handleChat(String clientId, Packet packet) {
        Optional<Session> optSession = sessionManager.find(clientId);
        if (optSession.isEmpty()) {
            return;
        }
        Session session = optSession.get();
        if (session.getRoomId() == null) {
            return;
        }
        String text = extractJsonString(packet.getJsonPayload(), "text");
        if (text == null || text.isBlank()) {
            text = extractJsonString(packet.getJsonPayload(), "message");
        }
        if (text != null && !text.isBlank()) {
            try {
                chatHandler.enqueue(new ChatMessage(session.getRoomId(), session.getPlayerName(), text));
            } catch (IllegalArgumentException ignored) {
                // Invalid message dropped.
            }
        }
    }

    private void handlePing(String clientId, Packet packet) {
        sessionManager.find(clientId).ifPresent(session -> {
            byte[] payload = packet.getJsonPayload() != null
                    ? packet.getJsonPayload().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            heartbeatManager.onPing(session, payload);
        });
    }

    private void handleDisconnect(String clientId, Packet packet) {
        sessionManager.handleGracefulDisconnect(clientId);
    }

    private void sendError(String clientId, String errorCode, String message) {
        String json = "{\"errorCode\":\"" + escapeJson(errorCode)
                + "\",\"message\":\"" + escapeJson(message) + "\"}";
        clientSender.sendToPlayer(clientId, new OutboundMessage(PacketType.ERROR, json.getBytes(StandardCharsets.UTF_8)));
    }

    public static String extractJsonString(String json, String field) {
        if (json == null || field == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

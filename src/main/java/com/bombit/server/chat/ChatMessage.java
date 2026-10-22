package com.bombit.server.chat;

public record ChatMessage(String roomId, String senderId, String senderName, String message) {
    public ChatMessage(String roomId, String senderName, String message) { this(roomId, null, senderName, message); }
    public ChatMessage {
        if (roomId == null || senderName == null || senderName.isBlank() || message == null
                || message.isBlank() || message.codePointCount(0, message.length()) > 256)
            throw new IllegalArgumentException("Invalid chat message");
    }
}

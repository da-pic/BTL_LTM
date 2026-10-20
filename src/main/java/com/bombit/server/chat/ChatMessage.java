package com.bombit.server.chat;

public record ChatMessage(String roomId, String senderName, String message) {
    public ChatMessage {
        if (roomId == null || roomId.isBlank() || senderName == null || senderName.isBlank()
                || message == null || message.isBlank() || message.length() > 100) {
            throw new IllegalArgumentException("Invalid chat message");
        }
    }
}

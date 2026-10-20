package com.bombit.server.dispatch;

import com.bombit.common.protocol.Packet;

/** Hợp đồng handler để từng module gameplay/kết nối tự đăng ký xử lý message. */
@FunctionalInterface
public interface MessageHandler {
    void handle(String clientId, Packet packet);
}

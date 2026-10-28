package com.bombit.server.session;

/** Từ chối phục hồi theo nghiệp vụ (ví dụ match đã kết thúc), không phải lỗi nội bộ server.
 * SessionManager vẫn rollback/đóng phiên đang bàn giao, nhưng không gắn cờ failure hệ thống.
 */
public final class SessionRecoveryRejectedException extends RuntimeException {
    public SessionRecoveryRejectedException(String message) { super(message); }
}

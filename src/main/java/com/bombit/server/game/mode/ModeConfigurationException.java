package com.bombit.server.game.mode;

import java.util.Objects;

/** Lỗi cấu hình có mã ổn định; controller NV06 mới chuyển sang ERROR trên wire. */
public final class ModeConfigurationException extends IllegalArgumentException {
    public enum Code {
        MODE_NOT_SUPPORTED, MODE_UNAVAILABLE, RULES_NOT_SUPPORTED,
        INVALID_CONFIGURATION, CLIENT_CAPABILITY_MISMATCH
    }

    private final Code code;

    public ModeConfigurationException(Code code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public Code code() { return code; }
}

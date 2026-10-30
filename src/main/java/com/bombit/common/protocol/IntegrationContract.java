package com.bombit.common.protocol;

/** Mặc định protocol 3/snapshot 6; hằng legacy chỉ dành cho endpoint chẩn đoán tách biệt. */
public final class IntegrationContract {
    public static final int PROTOCOL_VERSION = 3;
    /** Chỉ cho fixture/endpoint chẩn đoán legacy, không negotiate fallback ở lobby live. */
    public static final int LEGACY_PROTOCOL_VERSION = 2;
    public static final int SNAPSHOT_VERSION = 6;
    public static final int INPUT_STEP_MILLIS = 50;
    public static final int MAX_PLAYERS = 4;
    public static final int HEARTBEAT_INTERVAL_MILLIS = 2_000;
    public static final int HEARTBEAT_TIMEOUT_MILLIS = 10_000;
    public static final int RECONNECT_GRACE_MILLIS = 30_000;
    public static final int RESYNC_MIN_INTERVAL_MILLIS = 1_000;

    private IntegrationContract() { }
}

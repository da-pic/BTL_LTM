package com.bombit.common.protocol;

/** Technical defaults for integration protocol 2; gameplay balance is specified separately. */
public final class IntegrationContract {
    public static final int PROTOCOL_VERSION = 2;
    public static final int INPUT_STEP_MILLIS = 50;
    public static final int MAX_PLAYERS = 4;
    public static final int HEARTBEAT_INTERVAL_MILLIS = 2_000;
    public static final int HEARTBEAT_TIMEOUT_MILLIS = 10_000;
    public static final int RECONNECT_GRACE_MILLIS = 15_000;
    public static final int RESYNC_MIN_INTERVAL_MILLIS = 1_000;

    private IntegrationContract() { }
}

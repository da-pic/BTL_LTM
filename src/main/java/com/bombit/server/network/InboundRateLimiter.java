package com.bombit.server.network;

import com.bombit.common.protocol.PacketType;
import java.util.concurrent.TimeUnit;

/** Per captured connection. Counts decoded frames, including unknown or rejected requests. */
public final class InboundRateLimiter {
    public enum Decision { ALLOW, REJECT, CLOSE }
    private boolean initialized;
    private long window;
    private int total,chat,lists;
    public Decision check(short type,long now){
        if(!initialized||now-window>=TimeUnit.SECONDS.toNanos(1)){initialized=true;window=now;total=chat=lists=0;}
        if(++total>100)return Decision.CLOSE;
        if(type==PacketType.CHAT&&++chat>5)return Decision.REJECT;
        if(type==PacketType.LIST_ROOMS&&++lists>5)return Decision.REJECT;
        return Decision.ALLOW;
    }
}

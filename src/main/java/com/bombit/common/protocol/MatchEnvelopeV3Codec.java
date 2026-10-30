package com.bombit.common.protocol;

import com.bombit.common.dto.StreamContext;
import java.util.UUID;
import static com.bombit.common.protocol.JsonV3.*;

/** Header v3 có body riêng; không dùng decoder v2 như fallback khi v3 lỗi. */
public final class MatchEnvelopeV3Codec {
    private MatchEnvelopeV3Codec() { }
    public static Packet command(UUID match,Packet inner){return command(match,0,inner);}
    public static Packet command(UUID match,long timeline,Packet inner){if(timeline<0)throw new IllegalArgumentException("Invalid timeline");return packet(inner.getType(),body("matchId",match.toString(),"timelineEpoch",timeline,"authorityEpoch",0L,"body",object(inner.getJsonPayload(),4096)));}
    public static Packet update(UUID match,StreamContext stream,Packet inner){
        var result=packet(inner.getType(),body("matchId",match.toString(),"timelineEpoch",stream.timelineEpoch(),"authorityEpoch",stream.authorityEpoch(),"recipientPlayerId",stream.recipientPlayerId(),"viewEpoch",stream.viewEpoch(),"body",object(inner.getJsonPayload(),1048574)));
        try{PacketEncoder.encode(result);}catch(java.io.IOException e){throw new ProtocolRejection("PAYLOAD_TOO_LARGE");}return result;
    }
    public static Packet unwrapCommand(Packet p,UUID match){
        return unwrapCommand(p,match,0);
    }
    public static Packet unwrapCommand(Packet p,UUID match,long timeline){
        var m=fields(object(p.getJsonPayload(),4096),"matchId","timelineEpoch","authorityEpoch","body");check(m,match,timeline);
        return packet(p.getType(),map(m.get("body")));
    }
    public static Packet unwrapUpdate(Packet p,UUID match,StreamContext stream){
        var m=fields(object(p.getJsonPayload(),1048574),"matchId","timelineEpoch","authorityEpoch","recipientPlayerId","viewEpoch","body");check(m,match,stream.timelineEpoch());
        if(integer(m,"recipientPlayerId",1,Integer.MAX_VALUE)!=stream.recipientPlayerId()||integer(m,"viewEpoch",1,Long.MAX_VALUE)!=stream.viewEpoch())throw new ProtocolRejection("STALE_CONTEXT");
        return packet(p.getType(),map(m.get("body")));
    }
    private static void check(java.util.Map<String,Object> m,UUID match,long timeline){
        if(!uuid(m,"matchId").equals(match.toString()))throw new ProtocolRejection("STALE_MATCH");
        if(integer(m,"timelineEpoch",0,Long.MAX_VALUE)!=timeline||integer(m,"authorityEpoch",0,Long.MAX_VALUE)!=0)throw new ProtocolRejection("STALE_CONTEXT");
    }
}

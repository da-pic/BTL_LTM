package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.mode.paradox.ParadoxGameplayExtension;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Schema strict, không chấp nhận enum tùy ý hoặc epoch khác envelope. */
public final class ParadoxCodec {
    private ParadoxCodec(){}
    public static Packet rewind(long requestId){if(requestId<=0)throw new IllegalArgumentException("Invalid request");return packet(PacketType.REWIND,body("requestId",requestId));}
    public static Map<String,Object> frame(ParadoxFrame f){return body("type","PARADOX","timelineEpoch",f.timelineEpoch(),
            "simulationTick",f.simulationTick(),"availableCharges",f.availableCharges().stream().sorted().toList(),"cooldownRemainingMillis",f.cooldownRemainingMillis());}
    public static ParadoxFrame frame(Object raw,long sim,MatchStartMetadata metadata){
        var m=fields(raw,"type","timelineEpoch","simulationTick","availableCharges","cooldownRemainingMillis");
        if(!"PARADOX".equals(m.get("type")))throw new IllegalArgumentException("Wrong mode frame");
        long epoch=integer(m,"timelineEpoch",0,Long.MAX_VALUE);
        if(epoch!=metadata.stream().timelineEpoch()||integer(m,"simulationTick",0,Long.MAX_VALUE)!=sim)throw new ProtocolRejection("STALE_CONTEXT");
        var ids=new HashSet<Integer>();var roster=metadata.players().stream().map(MatchStartMetadata.Member::playerId).toList();
        for(var value:list(m.get("availableCharges"),4))if(!(value instanceof Long id)||id<=0||id>Integer.MAX_VALUE||!roster.contains(id.intValue())||!ids.add(id.intValue()))throw new IllegalArgumentException("Invalid charge roster");
        return new ParadoxFrame(epoch,sim,ids,integer(m,"cooldownRemainingMillis",0,3000));
    }
    public static Packet result(ParadoxGameplayExtension.RewindResult r){return packet(PacketType.REWIND_RESULT,body("playerId",r.playerId(),"requestId",r.requestId(),
            "requestedEpoch",r.requestedEpoch(),"outcome",r.outcome().name(),"serverTick",r.serverTick(),"simulationTick",r.simulationTick(),"timelineEpoch",r.timelineEpoch()));}
    public static ParadoxGameplayExtension.RewindResult result(Packet packet){
        if(packet.getType()!=PacketType.REWIND_RESULT)throw new IllegalArgumentException("Wrong result type");
        var m=fields(object(packet.getJsonPayload(),4096),"playerId","requestId","requestedEpoch","outcome","serverTick","simulationTick","timelineEpoch");
        long server=integer(m,"serverTick",0,Long.MAX_VALUE),sim=integer(m,"simulationTick",0,server);
        return new ParadoxGameplayExtension.RewindResult((int)integer(m,"playerId",1,Integer.MAX_VALUE),integer(m,"requestId",1,Long.MAX_VALUE),
                integer(m,"requestedEpoch",0,Long.MAX_VALUE),ParadoxGameplayExtension.Outcome.valueOf(text(m,"outcome",32)),server,sim,integer(m,"timelineEpoch",0,Long.MAX_VALUE));
    }
}

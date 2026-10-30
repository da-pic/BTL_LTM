package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Tagged result Classic; không dùng winnerPlayerId để giả kết quả đội. */
public final class GameplayV3Codec {
    private GameplayV3Codec() { }
    public static Map<String,Object> teamResultBody(TeamMatchResult result,GameMode mode){
        if(mode!=GameMode.CLASSIC&&mode!=GameMode.PARADOX)throw new IllegalArgumentException("Not Classic/Paradox");
        return body("type","TEAM","gameMode",mode.name(),"matchFormat","TEAM_2V2","tick",result.tick(),
                "winningTeam",result.winningTeam()==null?null:result.winningTeam().name(),"reason",result.reason().name());
    }
    public static TeamMatchResult teamResult(Object raw,GameMode mode){
        var m=fields(raw,"type","gameMode","matchFormat","tick","winningTeam","reason");
        if((mode!=GameMode.CLASSIC&&mode!=GameMode.PARADOX)||!"TEAM".equals(m.get("type"))||!mode.name().equals(m.get("gameMode"))
                ||!"TEAM_2V2".equals(m.get("matchFormat")))throw new ProtocolRejection("MODE_NOT_SUPPORTED");
        return new TeamMatchResult(integer(m,"tick",0,Long.MAX_VALUE),m.get("winningTeam")==null?null:TeamId.valueOf(text(m,"winningTeam",1)),
                TeamMatchResult.Reason.valueOf(text(m,"reason",32)));
    }
    public static Map<String,Object> resultBody(MatchResult result){
        return resultBody(result,GameMode.CLASSIC);
    }
    public static Map<String,Object> resultBody(MatchResult result,GameMode mode){
        if(mode!=GameMode.CLASSIC&&mode!=GameMode.PARADOX)throw new IllegalArgumentException("Not individual mode");
        var fields=new LinkedHashMap<String,Object>();fields.put("type","INDIVIDUAL");fields.put("gameMode",mode.name());
        fields.putAll(LobbyJson.object(GameplayPacketCodec.result(result).getJsonPayload()));return fields;
    }
    public static Packet result(MatchResult result){return packet(PacketType.GAME_OVER,resultBody(result));}
    public static MatchResult result(Object value){
        return result(value,GameMode.CLASSIC);
    }
    public static MatchResult result(Object value,GameMode mode){
        var m=fields(value,"type","gameMode","tick","winnerPlayerId","reason","rankings");
        if((mode!=GameMode.CLASSIC&&mode!=GameMode.PARADOX)||!"INDIVIDUAL".equals(m.get("type"))||!mode.name().equals(m.get("gameMode")))throw new ProtocolRejection("MODE_NOT_SUPPORTED");
        var copy=new LinkedHashMap<>(m);copy.remove("type");copy.remove("gameMode");return GameplayPacketCodec.decodeResult(packet(PacketType.GAME_OVER,copy));
    }
    public static Packet notice(long tick,GameNotice notice,MatchContext context,long origin){
        var legacy=GameplayPacketCodec.notice(tick,notice,context);var m=new LinkedHashMap<>(LobbyJson.object(legacy.getJsonPayload()));
        long occurred=((Number)m.remove("occurredAtMillis")).longValue();m.put("occurredAtMatchMillis",Math.max(0,occurred-origin));
        if(m.containsKey("explodeAtMillis"))m.put("explodeAtMatchMillis",Math.max(0,((Number)m.remove("explodeAtMillis")).longValue()-origin));
        return packet(legacy.getType(),m);
    }
}

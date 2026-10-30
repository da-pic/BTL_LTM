package com.bombit.common.protocol;

import com.bombit.common.dto.GameMode;
import java.util.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Handshake live v3: kiểm version/features trước ownership, không downgrade qua reconnect. */
public final class SessionV3Codec {
    public record Connect(String playerName,Set<String> features){public Connect{features=Set.copyOf(features);}}
    public record Reconnect(String playerId,String reconnectToken,Set<String> features){public Reconnect{features=Set.copyOf(features);}}
    public record Ack(String playerId,String reconnectToken,UUID connectionId,Set<GameMode> enabledModes){public Ack{enabledModes=Set.copyOf(enabledModes);}}
    private SessionV3Codec() { }
    private static void version(Map<String,Object> m){if(integer(m,"protocolVersion",0,Integer.MAX_VALUE)!=3)throw new ProtocolRejection("UNSUPPORTED_PROTOCOL");}
    private static Set<String> capabilities(Map<String,Object> m){
        var versions=new HashSet<Long>();for(var v:list(m.get("snapshotVersions"),4)){if(!(v instanceof Long n)||n<=0||n>Integer.MAX_VALUE||!versions.add(n))throw new IllegalArgumentException("Invalid versions");}
        if(!versions.contains(6L))throw new ProtocolRejection("UNSUPPORTED_SNAPSHOT");
        var features=new HashSet<String>();for(var v:list(m.get("features"),8)){if(!(v instanceof String s)||!s.matches("[A-Z][A-Z0-9_]{0,63}")||!features.add(s))throw new IllegalArgumentException("Invalid features");}
        if(!features.contains("CLASSIC_V1"))throw new ProtocolRejection("CLIENT_CAPABILITY_MISMATCH");return Set.copyOf(features);
    }
    public static Connect connect(Packet p){
        if(p.getType()!=PacketType.CONNECT)throw new IllegalArgumentException("Wrong type");var m=object(p.getJsonPayload(),8192);version(m);
        fields(m,"playerName","protocolVersion","snapshotVersions","features");
        var name=text(m,"playerName",32).strip();return new Connect(name,capabilities(m));
    }
    public static Reconnect reconnect(Packet p){
        if(p.getType()!=PacketType.RECONNECT)throw new IllegalArgumentException("Wrong type");var m=object(p.getJsonPayload(),8192);
        if(m.keySet().equals(Set.of("playerId","reconnectToken")))throw new ProtocolRejection("UNSUPPORTED_PROTOCOL");version(m);
        fields(m,"playerId","reconnectToken","protocolVersion","snapshotVersions","features");return new Reconnect(uuid(m,"playerId"),uuid(m,"reconnectToken"),capabilities(m));
    }
    public static Packet connectRequest(String name){return packet(PacketType.CONNECT,body("playerName",name,"protocolVersion",3,"snapshotVersions",List.of(6),"features",List.of("CLASSIC_V1","SHADOW_RELAY_V1","PARADOX_V1","TEAM_MATCH_V1")));}
    public static Packet reconnectRequest(String id,String token){return packet(PacketType.RECONNECT,body("playerId",id,"reconnectToken",token,"protocolVersion",3,"snapshotVersions",List.of(6),"features",List.of("CLASSIC_V1","SHADOW_RELAY_V1","PARADOX_V1","TEAM_MATCH_V1")));}
    public static Packet acknowledgement(String id,String token,UUID connection){return packet(PacketType.CONNECT_ACK,body("playerId",id,"reconnectToken",token,"serverVersion","multimode-contract-v1","protocolVersion",3,"snapshotVersion",6,"connectionId",connection.toString(),"heartbeatIntervalMs",2000,"heartbeatTimeoutMs",10000,"reconnectGraceMs",30000,"enabledModes",List.of("CLASSIC","SHADOW_RELAY","PARADOX")));}
    public static Ack decodeAcknowledgement(Packet p){
        if(p.getType()!=PacketType.CONNECT_ACK)throw new IllegalArgumentException("Wrong type");
        var m=fields(object(p.getJsonPayload(),8192),"playerId","reconnectToken","serverVersion","protocolVersion","snapshotVersion","connectionId","heartbeatIntervalMs","heartbeatTimeoutMs","reconnectGraceMs","enabledModes");version(m);
        if(integer(m,"snapshotVersion",1,99)!=6)throw new ProtocolRejection("UNSUPPORTED_SNAPSHOT");
        text(m,"serverVersion",128);for(var key:List.of("heartbeatIntervalMs","heartbeatTimeoutMs","reconnectGraceMs"))integer(m,key,1,86400000);
        var modes=new HashSet<GameMode>();for(var v:list(m.get("enabledModes"),4)){if(!(v instanceof String s)||!modes.add(GameMode.valueOf(s)))throw new IllegalArgumentException("Invalid mode list");}
        if(!modes.contains(GameMode.CLASSIC))throw new ProtocolRejection("MODE_UNAVAILABLE");return new Ack(uuid(m,"playerId"),uuid(m,"reconnectToken"),UUID.fromString(uuid(m,"connectionId")),modes);
    }
    public static Packet error(String code){return packet(PacketType.ERROR,body("errorCode",code,"message",code,"requestId",null,"matchId",null));}
    public static String errorCode(Packet p){
        if(p.getType()!=PacketType.ERROR)throw new IllegalArgumentException("Wrong type");var m=fields(object(p.getJsonPayload(),16384),"errorCode","message","requestId","matchId");
        text(m,"message",256);if(m.get("requestId")!=null)integer(m,"requestId",1,Long.MAX_VALUE);if(m.get("matchId")!=null)uuid(m,"matchId");return text(m,"errorCode",64);
    }
}

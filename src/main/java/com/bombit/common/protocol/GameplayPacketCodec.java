package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.event.*;
import com.bombit.server.game.item.ItemType;
import java.util.*;

/** Official item/death/result schemas. Notices never apply world effects on clients. */
public final class GameplayPacketCodec {
    private GameplayPacketCodec(){}
    public static Packet notice(long tick,GameNotice notice,MatchContext context){
        var e=notice.event();
        if(e instanceof BombPlacedEvent||e instanceof BombExplodedEvent)return BombPacketCodec.encodeNotice(tick,notice,context);
        var b=new LinkedHashMap<String,Object>();b.put("eventId",notice.eventId());b.put("tick",tick);b.put("occurredAtMillis",e.occurredAtMillis());
        if(e instanceof ItemSpawnedEvent v){
            b.put("itemId",v.itemId());b.put("itemType",v.itemType().name());b.put("x",v.position().x());b.put("y",v.position().y());
            return LobbyPacketCodec.packet(PacketType.ITEM_SPAWN,b);
        }
        if(e instanceof ItemPickedUpEvent v){
            b.put("itemId",v.itemId());b.put("itemType",v.itemType().name());b.put("playerId",context.playerId(v.playerId()).orElseThrow());
            return LobbyPacketCodec.packet(PacketType.ITEM_PICKED_UP,b);
        }
        var v=(PlayerDiedEvent)e;b.put("playerId",context.playerId(v.playerId()).orElseThrow());
        b.put("killerPlayerId",v.killerPlayerId()==null?null:context.playerId(v.killerPlayerId()).orElseThrow());b.put("cause",v.cause());
        return LobbyPacketCodec.packet(PacketType.PLAYER_DIE,b);
    }
    public static void validateNotice(Packet packet){
        if(packet.getType()==PacketType.BOMB_PLANTED||packet.getType()==PacketType.BOMB_EXPLODE){BombPacketCodec.decodeNotice(packet);return;}
        Set<String> fields=switch(packet.getType()){
            case PacketType.ITEM_SPAWN->Set.of("eventId","tick","occurredAtMillis","itemId","itemType","x","y");
            case PacketType.ITEM_PICKED_UP->Set.of("eventId","tick","occurredAtMillis","itemId","itemType","playerId");
            case PacketType.PLAYER_DIE->Set.of("eventId","tick","occurredAtMillis","playerId","killerPlayerId","cause");
            default->throw new IllegalArgumentException("Wrong notice type");
        };
        var b=LobbyPacketCodec.request(packet,fields);positive(b,"eventId");positive(b,"tick");number(b,"occurredAtMillis");
        if(packet.getType()==PacketType.PLAYER_DIE){
            LobbyPacketCodec.integer(b,"playerId",1,Integer.MAX_VALUE);
            if(b.get("killerPlayerId")!=null)LobbyPacketCodec.integer(b,"killerPlayerId",1,Integer.MAX_VALUE);
            if(!LobbyPacketCodec.text(b,"cause",32).equals("EXPLOSION"))throw new IllegalArgumentException("Invalid cause");
        }else{
            LobbyPacketCodec.integer(b,"itemId",1,Integer.MAX_VALUE);ItemType.valueOf(LobbyPacketCodec.text(b,"itemType",16));
            if(packet.getType()==PacketType.ITEM_SPAWN){LobbyPacketCodec.integer(b,"x",0,1_000_000);LobbyPacketCodec.integer(b,"y",0,1_000_000);}
            else LobbyPacketCodec.integer(b,"playerId",1,Integer.MAX_VALUE);
        }
    }
    public static Packet result(MatchResult r){
        var b=new LinkedHashMap<String,Object>();b.put("tick",r.tick());b.put("winnerPlayerId",r.winnerPlayerId());b.put("reason",r.reason().name());
        b.put("rankings",r.rankings().stream().map(v->Map.of("playerId",v.playerId(),"rank",v.rank())).toList());
        return LobbyPacketCodec.packet(PacketType.GAME_OVER,b);
    }
    public static MatchResult decodeResult(Packet packet){
        if(packet.getType()!=PacketType.GAME_OVER)throw new IllegalArgumentException("Wrong result type");
        var b=LobbyPacketCodec.request(packet,Set.of("tick","winnerPlayerId","reason","rankings"));
        long tick=number(b,"tick");Integer winner=b.get("winnerPlayerId")==null?null:LobbyPacketCodec.integer(b,"winnerPlayerId",1,Integer.MAX_VALUE);
        if(!(b.get("rankings") instanceof List<?> list)||list.size()>4)throw new IllegalArgumentException("Invalid rankings");
        var ranks=new ArrayList<MatchResult.Ranking>();
        for(var entry:list){var rank=LobbyJson.map(entry);if(!rank.keySet().equals(Set.of("playerId","rank")))throw new IllegalArgumentException("Invalid ranking fields");
            ranks.add(new MatchResult.Ranking(LobbyPacketCodec.integer(rank,"playerId",1,Integer.MAX_VALUE),LobbyPacketCodec.integer(rank,"rank",1,4)));}
        return new MatchResult(tick,winner,MatchResult.Reason.valueOf(LobbyPacketCodec.text(b,"reason",32)),ranks);
    }
    private static long number(Map<String,Object>b,String key){if(!(b.get(key)instanceof Long n))throw new IllegalArgumentException("Invalid integer");return n;}
    private static void positive(Map<String,Object>b,String key){if(number(b,key)<=0)throw new IllegalArgumentException("Expected positive integer");}
}

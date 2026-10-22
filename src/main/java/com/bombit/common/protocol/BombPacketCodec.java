package com.bombit.common.protocol;

import com.bombit.common.dto.GameNotice;
import com.bombit.common.dto.MatchContext;
import com.bombit.server.game.event.*;
import java.util.*;

/** Protocol-2 match-scoped commands and informational events; snapshots own all world changes. */
public final class BombPacketCodec {
    private BombPacketCodec() { }
    public static Packet plant(long requestId) {
        if(requestId<=0) throw new IllegalArgumentException("Invalid request ID");
        return LobbyPacketCodec.packet(PacketType.PLANT_BOMB,Map.of("requestId",requestId));
    }
    public static long decodePlant(Packet p) {
        if(p.getType()!=PacketType.PLANT_BOMB) throw new IllegalArgumentException("Wrong type");
        var b=LobbyPacketCodec.request(p,Set.of("requestId"));return positive(b,"requestId");
    }
    public static Packet encodeNotice(long tick,GameNotice notice,MatchContext match) {
        var b=new LinkedHashMap<String,Object>();b.put("eventId",notice.eventId());b.put("tick",tick);
        b.put("occurredAtMillis",notice.event().occurredAtMillis());
        if(notice.event() instanceof BombPlacedEvent e) {
            b.put("bombId",e.bombId());b.put("ownerPlayerId",match.playerId(e.ownerPlayerId()).orElseThrow());
            b.put("x",e.position().x());b.put("y",e.position().y());b.put("fuseMs",e.fuseMillis());
            b.put("explodeAtMillis",e.explodeAtMillis());b.put("range",e.range());
            return LobbyPacketCodec.packet(PacketType.BOMB_PLANTED,b);
        }
        var e=(BombExplodedEvent)notice.event();b.put("bombId",e.bombId());
        b.put("x",e.position().x());b.put("y",e.position().y());
        b.put("affectedCells",e.affectedCells().stream().sorted(Comparator.comparingInt(com.bombit.server.game.model.TilePosition::y)
                .thenComparingInt(com.bombit.server.game.model.TilePosition::x)).map(c->Map.of("x",c.x(),"y",c.y())).toList());
        return LobbyPacketCodec.packet(PacketType.BOMB_EXPLODE,b);
    }
    public record Notice(short type,long eventId,long tick,int bombId) { }
    public static Notice decodeNotice(Packet p) {
        Set<String> fields;
        if(p.getType()==PacketType.BOMB_PLANTED) fields=Set.of("eventId","tick","occurredAtMillis","bombId","ownerPlayerId","x","y","fuseMs","range","explodeAtMillis");
        else if(p.getType()==PacketType.BOMB_EXPLODE) fields=Set.of("eventId","tick","occurredAtMillis","bombId","x","y","affectedCells");
        else throw new IllegalArgumentException("Wrong event type");
        var b=LobbyPacketCodec.request(p,fields);
        int id=LobbyPacketCodec.integer(b,"bombId",1,Integer.MAX_VALUE);long tick=positive(b,"tick");
        long eventId=positive(b,"eventId");number(b,"occurredAtMillis");
        LobbyPacketCodec.integer(b,"x",0,1_000_000);LobbyPacketCodec.integer(b,"y",0,1_000_000);
        if(p.getType()==PacketType.BOMB_PLANTED) {
            LobbyPacketCodec.integer(b,"ownerPlayerId",1,Integer.MAX_VALUE);positive(b,"fuseMs");
            LobbyPacketCodec.integer(b,"range",1,1_000_000);number(b,"explodeAtMillis");
        } else {
            if(!(b.get("affectedCells") instanceof List<?> cells)||cells.isEmpty()) throw new IllegalArgumentException("Invalid explosion");
            var seen=new HashSet<Object>();
            for(Object c:cells) {
                var cell=LobbyJson.map(c);
                if(!cell.keySet().equals(Set.of("x","y"))||!seen.add(c)) throw new IllegalArgumentException("Invalid cell");
                LobbyPacketCodec.integer(cell,"x",0,1_000_000);LobbyPacketCodec.integer(cell,"y",0,1_000_000);
            }
        }
        return new Notice(p.getType(),eventId,tick,id);
    }
    private static long number(Map<String,Object> b,String key) {
        if(!(b.get(key) instanceof Long n)) throw new IllegalArgumentException("Invalid integer");return n;
    }
    private static long positive(Map<String,Object> b,String key) {
        long n=number(b,key);if(n<=0)throw new IllegalArgumentException("Invalid positive integer");return n;
    }
}

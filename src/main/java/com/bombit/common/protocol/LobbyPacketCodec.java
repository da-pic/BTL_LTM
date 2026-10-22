package com.bombit.common.protocol;

import java.util.*;

public final class LobbyPacketCodec {
    public record Player(String playerId,String username,String state) { }
    public record RoomView(String roomId,String roomName,String hostId,String state,int maxPlayers,List<Player> players) {
        public RoomView { players=List.copyOf(players); }
    }
    public record Chat(String roomId,String playerId,String username,String message) { }
    private LobbyPacketCodec(){ }
    public static Packet packet(short type,Map<String,?> body){return new Packet(type,LobbyJson.encode(body));}
    public static Map<String,Object> request(Packet packet,Set<String> fields){
        return request(packet,fields,Set.of());
    }
    public static Map<String,Object> request(Packet packet,Set<String> required,Set<String> optional){
        if(packet.getJsonPayload().length()>4096)throw new IllegalArgumentException("Request too large");
        var body=LobbyJson.object(packet.getJsonPayload());
        if(!body.keySet().containsAll(required)||body.keySet().stream().anyMatch(k->!required.contains(k)&&!optional.contains(k)))throw new IllegalArgumentException("Invalid fields");return body;
    }
    public static String text(Map<String,Object> body,String key,int max){
        if(!(body.get(key) instanceof String text))throw new IllegalArgumentException("Missing text");
        text=text.strip();if(text.isEmpty()||text.codePointCount(0,text.length())>max||text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid text");return text;
    }
    public static String roomId(Map<String,Object> body){String id=text(body,"roomId",36);if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Invalid room ID");return id;}
    public static int integer(Map<String,Object> body,String key,int min,int max){if(!(body.get(key) instanceof Long n)||n<min||n>max)throw new IllegalArgumentException("Invalid integer");return n.intValue();}
    public static boolean bool(Map<String,Object> body,String key){if(!(body.get(key) instanceof Boolean value))throw new IllegalArgumentException("Invalid boolean");return value;}
    private static Map<String,Object> roomBody(RoomView room){
        var body=new LinkedHashMap<String,Object>();body.put("roomId",room.roomId());body.put("roomName",room.roomName());body.put("hostId",room.hostId());body.put("state",room.state());body.put("maxPlayers",room.maxPlayers());
        body.put("players",room.players().stream().map(p->Map.of("playerId",p.playerId(),"username",p.username(),"state",p.state())).toList());return body;
    }
    public static Packet roomState(RoomView room){return packet(PacketType.ROOM_STATE,roomBody(room));}
    public static Packet roomList(List<RoomView> rooms){return packet(PacketType.LIST_ROOMS,Map.of("rooms",rooms.stream().map(LobbyPacketCodec::roomBody).toList()));}
    public static RoomView decodeRoom(Packet packet){if(packet.getType()!=PacketType.ROOM_STATE)throw new IllegalArgumentException("Wrong type");return decodeRoom(LobbyJson.object(packet.getJsonPayload()));}
    private static RoomView decodeRoom(Map<String,Object> body){
        var players=new ArrayList<Player>();for(Object entry:list(body.get("players"))){var p=LobbyJson.map(entry);players.add(new Player(text(p,"playerId",64),text(p,"username",32),text(p,"state",32)));}
        return new RoomView(text(body,"roomId",36),text(body,"roomName",64),text(body,"hostId",64),text(body,"state",16),integer(body,"maxPlayers",2,4),players);
    }
    public static List<RoomView> decodeRooms(Packet packet){var body=LobbyJson.object(packet.getJsonPayload());return list(body.get("rooms")).stream().map(v->decodeRoom(LobbyJson.map(v))).toList();}
    private static List<?> list(Object value){if(!(value instanceof List<?> list))throw new IllegalArgumentException("Expected list");return list;}
    public static Packet chat(Chat chat){return packet(PacketType.CHAT,Map.of("roomId",chat.roomId(),"playerId",chat.playerId(),"username",chat.username(),"message",chat.message()));}
    public static Chat decodeChat(Packet packet){var b=LobbyJson.object(packet.getJsonPayload());return new Chat(text(b,"roomId",36),text(b,"playerId",64),text(b,"username",32),text(b,"message",256));}
}

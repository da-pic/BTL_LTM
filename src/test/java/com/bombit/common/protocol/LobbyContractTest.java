package com.bombit.common.protocol;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LobbyContractTest {
    @Test void roomAndChatRoundTripThroughTcpFrameIncludingEscapesAndUnicode() throws Exception {
        String room=UUID.randomUUID().toString(),host=UUID.randomUUID().toString();
        var view=new LobbyPacketCodec.RoomView(room,"Ph\u00f2ng \"A\" \\",host,"WAITING",4,
                List.of(new LobbyPacketCodec.Player(host,"Chinh \ud83c\udfae","IN_ROOM")));
        var frame=PacketEncoder.encode(LobbyPacketCodec.roomState(view));
        assertEquals(view,LobbyPacketCodec.decodeRoom(PacketDecoder.decode(new java.io.ByteArrayInputStream(frame))));
        assertEquals(List.of(view),LobbyPacketCodec.decodeRooms(LobbyPacketCodec.roomList(List.of(view))));
        var chat=new LobbyPacketCodec.Chat(room,host,"Chinh \ud83c\udfae","hello \"world\" \\ \u0111");
        assertEquals(chat,LobbyPacketCodec.decodeChat(LobbyPacketCodec.chat(chat)));
        assertEquals("CHAT",PacketType.nameOf((short)0x0017));assertFalse(PacketType.isKnown((short)0x0050));
    }
    @Test void boundedJsonRejectsDuplicateKeysInvalidEscapesAndTrailingOrOversizedInput() {
        for(String invalid:List.of("{\"ready\":true,\"ready\":false}","{}{}","{\"a\":01}","{\"a\":1.0}",
                "{\"a\":9223372036854775808}","{\"a\":\"\\uD800\"}","{\"a\":\"\\u+041\"}","{\"a\":[[[[[[[[[[0]]]]]]]]]]}"))
            assertThrows(IllegalArgumentException.class,()->LobbyJson.object(invalid),invalid);
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.request(new Packet(PacketType.READY," ".repeat(4097)),Set.of("ready")));
    }
    @Test void requestsRejectWrongTypesUnknownFieldsAndForgedIdentifiers() {
        var b=LobbyPacketCodec.request(new Packet(PacketType.READY,"{\"roomId\":\""+UUID.randomUUID()+"\",\"ready\":true}"),Set.of("ready"),Set.of("roomId"));
        assertTrue(LobbyPacketCodec.bool(b,"ready"));assertNotNull(LobbyPacketCodec.roomId(b));
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.request(new Packet(PacketType.READY,"{\"ready\":true,\"playerId\":5}"),Set.of("ready")));
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.bool(Map.of("ready","true"),"ready"));
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.integer(Map.of("maxPlayers",4.0),"maxPlayers",2,4));
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.roomId(Map.of("roomId","1-1-1-1-1")));
        assertThrows(IllegalArgumentException.class,()->LobbyPacketCodec.text(Map.of("message","bad\nchat"),"message",256));
    }
    @Test void endCommandIsScopedAndPacketCodesAreUnique() throws Exception {
        UUID match=UUID.randomUUID();var p=MatchEnvelopeCodec.wrap(match,new Packet(PacketType.END_GAME,"{}"));
        assertEquals("{}",MatchEnvelopeCodec.unwrap(p,match).getJsonPayload());
        assertThrows(java.io.IOException.class,()->MatchEnvelopeCodec.unwrap(p,UUID.randomUUID()));
        assertEquals("LIST_ROOMS",PacketType.nameOf((short)0x0018));
        assertEquals("END_GAME",PacketType.nameOf((short)0x0035));
    }
}

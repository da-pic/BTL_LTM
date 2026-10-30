package com.bombit.common.protocol;

import com.bombit.common.dto.*;
import com.bombit.server.game.mode.ModeRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bombit.common.protocol.JsonV3.*;

/** Kiểm schema v3 thực, không dùng decoder legacy để làm request sai thành hợp lệ. */
class ProtocolV3Test {
    private static Packet connect(Object... pairs){return packet(PacketType.CONNECT,body(pairs));}
    @Test void handshakeNegotiatesSixAndRejectsOldWireUnknownFieldsAndMissingFeatures(){
        assertEquals("Chinh",SessionV3Codec.connect(SessionV3Codec.connectRequest("Chinh")).playerName());
        assertEquals("UNSUPPORTED_PROTOCOL",assertThrows(ProtocolRejection.class,()->SessionV3Codec.connect(SessionPacketCodec.connectRequest("old"))).code());
        assertEquals("UNSUPPORTED_SNAPSHOT",assertThrows(ProtocolRejection.class,()->SessionV3Codec.connect(connect("playerName","x","protocolVersion",3,"snapshotVersions",List.of(5),"features",List.of("CLASSIC_V1")))).code());
        assertEquals("CLIENT_CAPABILITY_MISMATCH",assertThrows(ProtocolRejection.class,()->SessionV3Codec.connect(connect("playerName","x","protocolVersion",3,"snapshotVersions",List.of(6),"features",List.of()))).code());
        assertThrows(IllegalArgumentException.class,()->SessionV3Codec.connect(connect("playerName","x","protocolVersion",3,"snapshotVersions",List.of(6,6),"features",List.of("CLASSIC_V1"))));
        var valid=object(SessionV3Codec.connectRequest("x").getJsonPayload(),8192);valid.put("gameMode","SHADOW_RELAY");assertThrows(IllegalArgumentException.class,()->SessionV3Codec.connect(packet(PacketType.CONNECT,valid)));
    }
    @Test void reconnectCannotBypassVersionAndCanonicalIdentityChecks(){
        String id=UUID.randomUUID().toString(),token=UUID.randomUUID().toString();
        var decoded=SessionV3Codec.reconnect(SessionV3Codec.reconnectRequest(id,token));assertEquals(id,decoded.playerId());
        assertEquals("UNSUPPORTED_PROTOCOL",assertThrows(ProtocolRejection.class,()->SessionV3Codec.reconnect(SessionPacketCodec.reconnectRequest(id,token))).code());
        var m=object(SessionV3Codec.reconnectRequest(id,token).getJsonPayload(),8192);m.put("playerId","1-1-1-1-1");
        assertThrows(IllegalArgumentException.class,()->SessionV3Codec.reconnect(packet(PacketType.RECONNECT,m)));
        m.put("playerId",id);m.put("protocolVersion",2);assertEquals("UNSUPPORTED_PROTOCOL",assertThrows(ProtocolRejection.class,()->SessionV3Codec.reconnect(packet(PacketType.RECONNECT,m))).code());
    }
    @Test void jsonRejectsDuplicateKeysInvalidUnicodeDeepNestingAndIntegerCoercion(){
        assertThrows(IllegalArgumentException.class,()->object("{\"v\":1,\"v\":2}",4096));
        assertThrows(IllegalArgumentException.class,()->object("{\"v\":\"\\ud800\"}",4096));
        assertThrows(IllegalArgumentException.class,()->object("{\"v\":"+"[".repeat(18)+"0"+"]".repeat(18)+"}",4096));
        assertThrows(IllegalArgumentException.class,()->integer(object("{\"sequence\":1e0}",4096),"sequence",1,Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->object("{\"v\":9223372036854775808}",4096));
        assertThrows(IllegalArgumentException.class,()->object("{\"v\":1e999}",4096));
        assertThrows(ProtocolRejection.class,()->object("{\"v\":\""+"ắ".repeat(1500)+"\"}",4096));
        assertEquals(24.0,number(object("{\"v\":24.0}",4096),"v"));
        assertEquals("😀",text(object("{\"v\":\"\\ud83d\\ude00\"}",4096),"v",1));
    }
    @Test void configurationAndUnavailableDescriptorsRoundTripWithoutFakeFields(){
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());var config=registry.resolvePublic(GameMode.CLASSIC);
        assertEquals(config,MetadataV3Codec.configuration(object(encode(MetadataV3Codec.configuration(config)),16384)));
        for(var d:registry.descriptors())assertEquals(d,MetadataV3Codec.descriptor(object(encode(MetadataV3Codec.descriptor(d)),8192)));
        var raw=MetadataV3Codec.configuration(config);var forged=new LinkedHashMap<>(map(raw.get("effectiveRules")));forged.put("scan",Map.of());raw.put("effectiveRules",forged);
        assertThrows(IllegalArgumentException.class,()->MetadataV3Codec.configuration(raw));
    }
    @Test void envelopeSeparatesRecipientMatchAndEpochAndDoesNotFlattenBody(){
        var match=UUID.randomUUID();var stream=new StreamContext(1,3,0,0);
        var inner=packet(PacketType.MOVE,body("sequence",1,"direction","RIGHT","moving",true));
        var command=MatchEnvelopeV3Codec.command(match,inner);
        assertEquals(object(inner.getJsonPayload(),4096),object(MatchEnvelopeV3Codec.unwrapCommand(command,match).getJsonPayload(),4096));
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapCommand(command,UUID.randomUUID()));
        var update=MatchEnvelopeV3Codec.update(match,stream,packet(PacketType.PLANT_RESULT,Map.of("ok",true)));
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapUpdate(update,match,new StreamContext(2,3,0,0)));
        assertThrows(ProtocolRejection.class,()->MatchEnvelopeV3Codec.unwrapUpdate(update,match,new StreamContext(1,2,0,0)));
        assertThrows(IllegalArgumentException.class,()->MatchEnvelopeV3Codec.unwrapCommand(update,match));
    }
}

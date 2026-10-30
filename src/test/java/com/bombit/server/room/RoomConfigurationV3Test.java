package com.bombit.server.room;

import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.runtime.*;
import com.bombit.server.session.Session;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Transactions room không cần socket kết nối; Session chỉ là fixture identity. */
class RoomConfigurationV3Test {
    private static Session peer(String name){var s=new Session(name,new Socket());s.completeHandshake(name);return s;}
    @Test void configurationChangeResetsReadyAtomicallyButThemeAndNoopDoNot(){
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());var rooms=new RoomManager(4);var host=peer("host");var guest=peer("guest");
        try{var initial=registry.resolvePublic(GameMode.CLASSIC);var room=rooms.createRoom("test",host,4,initial);assertTrue(rooms.joinRoom(room.getRoomId(),guest));
            assertTrue(room.setReady(guest,true,1));assertTrue(room.canStartGame());
            assertFalse(room.changeConfiguration(registry.resolvePublic(GameMode.CLASSIC)));assertSame(initial,room.configuration().orElseThrow());
            room.setMapSelection(MapSelection.ICE);assertEquals(1,room.configurationRevision());assertTrue(room.canStartGame());
            var different=new ModeRegistry(ClassicRulesAdapter.publicRules(new BombRules(2000,1,1),new MatchRules(10000,.75,5,8,144,8,2,99),new PlayerMovementRules(24,80))).resolvePublic(GameMode.CLASSIC);
            assertTrue(room.changeConfiguration(different));assertEquals(2,room.configurationRevision());assertFalse(room.canStartGame());assertNull(room.readyRevision(guest.getPlayerId()));
            assertEquals("CONFIGURATION_STALE",assertThrows(ProtocolRejection.class,()->room.setReady(guest,true,1)).code());
            assertTrue(room.setReady(guest,true,2));assertTrue(room.canStartGame());
            var view=RoomManager.viewV3(room);assertEquals(view,MetadataV3Codec.decodeRoom(MetadataV3Codec.roomState(view)));
            assertEquals(4,view.maxPlayers());assertEquals(MapSelection.ICE,view.mapSelection());
        }finally{host.closeSocket();guest.closeSocket();}
    }
    @Test void classicMembershipRetainsOtherGuestsReadinessAndPreservesModeVersusTheme(){
        var rooms=new RoomManager(4);var host=peer("h");var a=peer("a");var b=peer("b");
        try{var room=rooms.createRoom("r",host,4,new ModeRegistry(EffectiveRules.Common.shadowRelayV1()).resolvePublic(GameMode.CLASSIC));
            rooms.joinRoom(room.getRoomId(),a);room.setReady(a,true,1);rooms.joinRoom(room.getRoomId(),b);
            assertEquals(1,room.readyRevision(a.getPlayerId()));assertFalse(room.canStartGame());
            rooms.leaveRoom(b);assertTrue(room.canStartGame());room.setMapSelection(MapSelection.FOREST);
            assertEquals(GameMode.CLASSIC,room.configuration().orElseThrow().gameMode());assertEquals(1,room.configurationRevision());
        }finally{host.closeSocket();a.closeSocket();b.closeSocket();}
    }
}

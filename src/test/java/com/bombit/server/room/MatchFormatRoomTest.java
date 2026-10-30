package com.bombit.server.room;

import com.bombit.common.dto.*;
import com.bombit.server.game.mode.ModeRegistry;
import com.bombit.server.session.*;
import java.net.Socket;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MatchFormatRoomTest {
    @Test void playingRosterDoesNotRenumberTeamsAfterRemovalAndWaitingRosterCanReset(){
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());var rooms=new RoomManager(4);
        var host=peer("host");var a=peer("a");var b=peer("b");var c=peer("c");
        try{
            var room=rooms.createRoom("stable teams",host,4,registry.resolvePublic(GameMode.PARADOX,MatchFormat.TEAM_2V2));
            for(var s:List.of(a,b,c))rooms.joinRoom(room.getRoomId(),s);
            room.setState(Room.State.PLAYING);for(var s:List.of(host,a,b,c))s.setState(SessionState.PLAYING);
            room.removePlayer(a.getPlayerId());var view=RoomManager.viewV3(room);
            assertEquals(List.of(1,3,4),view.players().stream().map(RoomStateView.Member::slot).toList());
            assertEquals(TeamId.B,view.players().stream().filter(p->p.sessionId().equals(c.getPlayerId())).findFirst().orElseThrow().team());
            assertEquals(view,com.bombit.common.protocol.MetadataV3Codec.decodeRoom(com.bombit.common.protocol.MetadataV3Codec.roomState(view)));
            long oldRevision=room.configurationRevision();room.setState(Room.State.WAITING);for(var s:List.of(host,b,c))s.setState(SessionState.IN_ROOM);
            assertEquals(oldRevision+1,room.configurationRevision());
            assertEquals(List.of(1,2,3),RoomManager.viewV3(room).players().stream().map(RoomStateView.Member::slot).toList());
        }finally{for(var s:List.of(host,a,b,c))s.closeSocket();}
    }
    private Session peer(String name){var s=new Session(name,new Socket());s.completeHandshake(name);return s;}
    @Test void switchingFormatChangesRevisionCapacityAndInvalidatesReady(){
        var registry=new ModeRegistry(EffectiveRules.Common.shadowRelayV1());
        for(var mode:List.of(GameMode.CLASSIC,GameMode.PARADOX)){
            var host=peer("host");var a=peer("a");var b=peer("b");var c=peer("c");var rooms=new RoomManager(4);
            try{
                var room=rooms.createRoom("format",host,2,registry.resolvePublic(mode));rooms.joinRoom(room.getRoomId(),a);
                room.setReady(a,true,room.configurationRevision());assertTrue(room.canStartGame());long old=room.configurationRevision();
                assertTrue(room.changeConfiguration(room.configuration().orElseThrow().withFormat(MatchFormat.TEAM_2V2)));
                assertEquals(old+1,room.configurationRevision());assertEquals(4,room.getMaxPlayers());assertEquals(SessionState.IN_ROOM,a.getState());assertFalse(room.canStartGame());
                assertEquals(List.of(TeamId.A,TeamId.B),RoomManager.viewV3(room).players().stream().map(RoomStateView.Member::team).toList());
                room.setReady(a,true,room.configurationRevision());rooms.joinRoom(room.getRoomId(),b);assertEquals(SessionState.IN_ROOM,a.getState());
                rooms.joinRoom(room.getRoomId(),c);for(var guest:List.of(a,b,c))room.setReady(guest,true,room.configurationRevision());assertTrue(room.canStartGame());
                assertTrue(room.changeConfiguration(room.configuration().orElseThrow().withFormat(MatchFormat.INDIVIDUAL)));
                assertTrue(RoomManager.viewV3(room).players().stream().allMatch(p->p.team()==null));
                assertEquals(SessionState.IN_ROOM,a.getState());assertFalse(room.canStartGame());
            }finally{for(var s:List.of(host,a,b,c))s.closeSocket();}
        }
    }
}

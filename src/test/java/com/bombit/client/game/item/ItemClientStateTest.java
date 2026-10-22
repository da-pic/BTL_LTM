package com.bombit.client.game.item;

import com.bombit.client.game.movement.MovementClientController;
import com.bombit.client.network.ClientNetwork;
import com.bombit.common.dto.*;
import com.bombit.common.protocol.*;
import com.bombit.server.game.core.GameState;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemClientStateTest {
    private static GameStateUpdate full(long tick,int quota,boolean item){
        return GameStateUpdate.fullStateModel(tick,3,3,32,GameState.MatchState.RUNNING,Collections.nCopies(9,TileType.EMPTY),
                List.of(new GameStateUpdate.PlayerData(1,32,32,24,24,150,Direction.DOWN,false,true,0)))
                .withEntities(item?List.of(new EntityData(EntityData.Kind.ITEM,1,1,1,"",0,0)):List.of(),List.of())
                .withBombFrame(new BombFrame(1000,List.of())).withMatchFrame(new MatchFrame(10,List.of(new MatchFrame.Stats(1,quota,2)),null));
    }
    @Test void duplicatePickupAndDeathEventsNeverApplyStatsDamageOrRemoveItemsAndFullResyncDoesNotStack()throws Exception{
        var match=UUID.randomUUID();var failures=new ArrayList<Throwable>();
        try(var net=new ClientNetwork();var client=new MovementClientController(net,1,Runnable::run,()->{},failures::add,match,false)){
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(full(1,1,true))));
            var pickup=MatchEnvelopeCodec.wrap(match,new Packet(PacketType.ITEM_PICKED_UP,
                    "{\"eventId\":2,\"tick\":2,\"occurredAtMillis\":1000,\"itemId\":1,\"playerId\":1,\"itemType\":\"BOMB_UP\"}"));
            client.acceptPacket(pickup);client.acceptPacket(pickup);
            assertTrue(client.world().getEntities().containsKey("ITEM:1"));assertEquals(1,client.world().getMatchFrame().players().get(0).bombLimit());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encode(full(2,2,false))));
            client.acceptPacket(pickup);client.acceptPacket(pickup);
            var death=MatchEnvelopeCodec.wrap(match,new Packet(PacketType.PLAYER_DIE,
                    "{\"eventId\":3,\"tick\":2,\"occurredAtMillis\":1000,\"playerId\":1,\"killerPlayerId\":null,\"cause\":\"EXPLOSION\"}"));
            client.acceptPacket(death);client.acceptPacket(death);assertTrue(client.world().getPlayers().get(1).isAlive());
            client.acceptPacket(MatchEnvelopeCodec.wrap(match,SnapshotPacketCodec.encodeFullSync(full(3,2,false))));
            client.acceptPacket(pickup);assertEquals(2,client.world().getMatchFrame().players().get(0).bombLimit());
            assertTrue(client.world().getEntities().isEmpty());assertTrue(failures.isEmpty());
        }
    }
}

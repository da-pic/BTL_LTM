package com.bombit.server.chat;

import com.bombit.server.room.RoomManager;
import com.bombit.server.session.Session;
import java.net.Socket;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class ChatFaultTest {
    @Test void failingRecipientDoesNotKillWorkerOrStarveHealthyRecipient()throws Exception{
        var rooms=new RoomManager(4);var a=new Session("A",new Socket());var b=new Session("B",new Socket());
        a.completeHandshake("A");b.completeHandshake("B");a.setOutboundSender((s,m)->{});b.setOutboundSender((s,m)->{});
        var room=rooms.createRoom("fault",a,2);rooms.joinRoom(room.getRoomId(),b);var delivered=new CountDownLatch(2);
        var chat=new ChatHandler(rooms,(s,m)->{if(s==a)throw new IllegalStateException("injected recipient");delivered.countDown();},2);
        try(chat){
            assertTrue(chat.enqueue(new ChatMessage(room.getRoomId(),a.getPlayerId(),"A","first")));
            assertTrue(chat.enqueue(new ChatMessage(room.getRoomId(),a.getPlayerId(),"A","second")));
            assertTrue(delivered.await(2,TimeUnit.SECONDS));assertTrue(chat.isAlive());assertTrue(chat.failure().isPresent());
        }finally{a.closeSocket();b.closeSocket();}
        assertFalse(chat.isAlive());assertEquals(0,chat.pendingMessages());assertFalse(chat.enqueue(new ChatMessage(room.getRoomId(),"A","A","closed")));
    }
}

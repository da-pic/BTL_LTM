package com.bombit.server.game.runtime;

import com.bombit.server.game.model.TileType;

import com.bombit.server.game.model.Direction;

import com.bombit.server.game.core.*;
import com.bombit.server.game.model.*;
import com.bombit.server.game.movement.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Hồi quy contract giai đoạn 0 và runtime: FIFO, concurrency, queue đầy, snapshot, lifecycle.
 * Không cần socket hoặc JUnit để chạy CLI. Đồng bộ bằng latch thay vì dựa vào khoảng ngủ đoán trước.
 */
public final class RuntimeContractCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void await(BooleanSupplier condition,String message)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
        check(condition.getAsBoolean(),message);
    }
    private static GameState state(){
        GameState state=new GameState(new GameMap(8,6,32));
        state.addPlayer(new Player(1,32,32,24,24,100));state.startMatch();return state;
    }
    public static void main(String[] args)throws Exception{
        queueAndConcurrency();roomsAndSnapshots();extensionFailure();
        System.out.println("PASS: runtime contract, FIFO, concurrent submit, queue limits, collision, immutable snapshot, thread cleanup");
    }
    private static void queueAndConcurrency()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        List<GameCommand> seen=new CopyOnWriteArrayList<>();
        GameplayExtension extension=new GameplayExtension(){
            public void processCommand(GameCommand command){seen.add(command);}
            public void update(long now){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        };
        GameLoop small=new GameLoop(state(),new MovementSystem(),extension,2,50_000_000L);
        small.start();
        try{
            check(entered.await(2,TimeUnit.SECONDS),"loop entered extension");
            GameCommand a=new PlantBombCommand(1),b=new StopMovingCommand(1);
            check(small.submitCommand(a)&&small.submitCommand(b),"accept within capacity");
            check(!small.submitCommand(new PlantBombCommand(1))&&small.getRejectedCommandCount()==1,"reject and count queue full");
            release.countDown();await(()->seen.size()==2,"commands processed");
            check(seen.get(0)==a&&seen.get(1)==b,"global FIFO");
        }finally{release.countDown();small.stop();}
        check(!small.isThreadAlive()&&!small.submitCommand(new PlantBombCommand(1)),"stop joins thread and rejects submit");

        CountDownLatch gate=new CountDownLatch(1),ready=new CountDownLatch(1);
        List<GameCommand> received=new CopyOnWriteArrayList<>();
        GameplayExtension concurrentExtension=new GameplayExtension(){
            public void processCommand(GameCommand c){received.add(c);}
            public void update(long now){ready.countDown();try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        };
        GameLoop loop=new GameLoop(state(),new MovementSystem(),concurrentExtension,512,50_000_000L);
        List<GameCommand> first=new ArrayList<>(),second=new ArrayList<>();
        for(int i=0;i<100;i++){first.add(new PlantBombCommand(1));second.add(new PlantBombCommand(2));}
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        loop.start();
        try{
            check(ready.await(2,TimeUnit.SECONDS),"ready for producer threads");
            Thread t1=new Thread(()->submitAll(loop,first,error)),t2=new Thread(()->submitAll(loop,second,error));
            t1.start();t2.start();t1.join(2000);t2.join(2000);
            check(!t1.isAlive()&&!t2.isAlive()&&error.get()==null,"two producers completed");
            gate.countDown();await(()->received.size()==200,"no lost or duplicate commands");
            check(new HashSet<>(received).size()==200,"all command identities preserved");
            for(List<GameCommand> producer:List.of(first,second)){
                int last=-1;for(GameCommand command:producer){int index=received.indexOf(command);check(index>last,"producer FIFO");last=index;}
            }
        }finally{gate.countDown();loop.stop();}
        check(!loop.isThreadAlive(),"concurrent loop cleanup");
    }
    private static void submitAll(GameLoop loop,List<GameCommand> commands,java.util.concurrent.atomic.AtomicReference<Throwable> error){
        try{for(GameCommand c:commands)check(loop.submitCommand(c),"unexpected rejection");}catch(Throwable e){error.set(e);}
    }
    private static void roomsAndSnapshots()throws Exception{
        Map<String,List<GameCommand>> commands=new ConcurrentHashMap<>();
        try(GameRuntimeManager manager=new GameRuntimeManager(id->{
            GameMap map=new GameMap(8,6,32);map.setTile(3,1,TileType.HARD_WALL);return map;
        },(id,state)->new GameplayExtension(){
            public void processCommand(GameCommand c){
                commands.computeIfAbsent(id,key->new CopyOnWriteArrayList<>()).add(c);
                if(c instanceof PlantBombCommand){state.getMap().setTile(2,2,TileType.BREAKABLE_BLOCK);}
            }
            public void update(long now){ }
        })){
            var spawn=List.of(new PlayerSpawn("uuid",1,32,32,24,24,100));
            GameRuntime a=manager.startGame("A",spawn),b=manager.startGame("B",spawn);
            await(()->a.createUpdate()!=null&&b.createUpdate()!=null,"initial snapshots");
            GameStateUpdate saved=a.createUpdate();double original=saved.getPlayers().get(0).getX();
            check(manager.findGame("A").orElseThrow()==a&&a.playerId("uuid").orElseThrow()==1,"manager contract");
            manager.submit("A",new MoveCommand(1,Direction.RIGHT));
            // Đến tường thì không có delta rỗng, nên latest tick không nhất thiết tăng mỗi tick.
            await(()->a.createUpdate().getPlayerChanges().stream().anyMatch(p->p.getX()==72),"movement reaches wall");
            manager.submit("A",new StopMovingCommand(1));manager.submit("A",new PlantBombCommand(1));
            await(()->commands.get("A").size()>=3,"extension receives all commands");
            a.requestFullState();
            GameStateUpdate current=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){var u=a.pollUpdate(200,TimeUnit.MILLISECONDS);if(u!=null&&u.isFullState()&&u.getTick()>1){current=u;break;}}
            check(current!=null&&current.getPlayers().get(0).getX()<=72,"no hard wall penetration");
            check(!current.getPlayers().get(0).isMoving(),"STOP stops movement");
            check(b.createUpdate().getPlayers().get(0).getX()==32,"rooms independent");
            check(saved.getPlayers().get(0).getX()==original&&saved.mapTiles().get(2*8+2)==TileType.EMPTY,"snapshot survives subsequent world changes");
            try{saved.mapTiles().clear();throw new AssertionError("mutable map snapshot");}catch(UnsupportedOperationException expected){ }
            try{saved.getPlayers().clear();throw new AssertionError("mutable players snapshot");}catch(UnsupportedOperationException expected){ }
            manager.stopGame("A");check(!a.isThreadAlive()&&b.isRunning(),"stop room A keeps B alive");
            manager.stopGame("B");check(!b.isThreadAlive()&&manager.findGame("B").isEmpty(),"stop removes runtime");
            check(!manager.submit("missing",new PlantBombCommand(1)),"missing room rejected");
        }
        // Kiểm tra block, biên map, player chết và model chung không phụ thuộc client.
        GameState state=state();state.getMap().setTile(2,1,TileType.BREAKABLE_BLOCK);
        MovementSystem movement=new MovementSystem();movement.applyCommand(state,new MoveCommand(1,Direction.RIGHT));
        for(int i=0;i<20;i++)movement.update(state,.05);
        check(state.getPlayer(1).getX()<=40,"breakable block collision");
        movement.applyCommand(state,new MoveCommand(1,Direction.LEFT));
        for(int i=0;i<20;i++)movement.update(state,.05);
        check(state.getPlayer(1).getX()>=0,"map boundary");
        state.getPlayer(1).setAlive(false);check(!movement.applyCommand(state,new MoveCommand(1,Direction.RIGHT)),"dead player cannot move");
        check(new TilePosition(1,1).move(Direction.UP,1).equals(new TilePosition(1,0)),"shared tile model");
        GameState sampled=state();
        check(movement.applyCommand(sampled,new MoveCommand(1,Direction.RIGHT,1)),"sampled MOVE accepted");
        check(movement.applyCommand(sampled,new StopMovingCommand(1))&&!sampled.getPlayer(1).isMoving(),"server STOP accepted for sampled player");
    }
    private static void extensionFailure()throws Exception{
        GameLoop loop=new GameLoop(state(),new MovementSystem(),new GameplayExtension(){
            public void processCommand(GameCommand c){ }
            public void update(long now){throw new IllegalStateException("test failure");}
        },4,50_000_000L);
        loop.start();await(()->!loop.isRunning(),"extension failure terminates loop");loop.stop();
        check(loop.getFailure()!=null&&!loop.isThreadAlive(),"failure observable and thread cleaned");
    }
}

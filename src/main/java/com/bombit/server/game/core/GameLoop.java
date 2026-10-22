package com.bombit.server.game.core;

import com.bombit.server.game.movement.*;
import com.bombit.server.game.runtime.GameplayExtension;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Một luồng sở hữu state: command FIFO → movement → extension TV3 → full/delta.
 * Network chỉ submit hoặc đọc snapshot bất biến, không đọc GameState trực tiếp.
 * Thay đổi lifecycle dùng command riêng; gameplay TV3 chỉ đi qua GameplayExtension.
 */
public class GameLoop {
    private final GameState state;
    private final MovementSystem movement;
    private final GameplayExtension extension;
    private final BlockingQueue<GameCommand> commands;
    private final BlockingQueue<GameStateUpdate> outbound = new LinkedBlockingQueue<>(1024);
    private final Set<Integer> frozen = ConcurrentHashMap.newKeySet();
    private final StateDeltaBuilder builder = new StateDeltaBuilder();
    private final AtomicBoolean fullRequested = new AtomicBoolean();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final long periodNanos;
    private volatile boolean running;
    private volatile long tick, processed, outboundFull;
    private volatile Throwable failure;
    private volatile GameStateUpdate latest;
    private Thread thread;
    public GameLoop(GameState state) { this(state,new MovementSystem()); }
    public GameLoop(GameState state,MovementSystem movement) {
        this(state,movement,GameplayExtension.none(),4096,50_000_000L);
    }
    /** Constructor cấu hình giúp test queue nhỏ; bước mô phỏng lấy từ periodNanos, không lấy từ client. */
    public GameLoop(GameState state,MovementSystem movement,GameplayExtension extension,int capacity,long periodNanos) {
        if(capacity<=0 || periodNanos<=0 || periodNanos>100_000_000L) throw new IllegalArgumentException("Invalid loop config");
        this.state=Objects.requireNonNull(state);this.movement=Objects.requireNonNull(movement);
        this.extension=Objects.requireNonNull(extension);this.commands=new LinkedBlockingQueue<>(capacity);
        this.periodNanos=periodNanos;
    }
    public synchronized void start() {
        if(thread!=null) throw new IllegalStateException("Loop starts once");
        if(state.getMatchState()!=GameState.MatchState.RUNNING) throw new IllegalStateException("Match not running");
        running=true;thread=new Thread(this::runLoop,"GameLoop-"+Integer.toHexString(System.identityHashCode(this)));thread.start();
    }
    /** offer không chờ; chỉ đếm queue-full, tách biệt với submit sau khi loop đã đóng. */
    public synchronized boolean submitCommand(GameCommand command) {
        Objects.requireNonNull(command);
        if(!running) return false;
        if(commands.offer(command)) return true;
        rejectedFull.incrementAndGet();return false;
    }
    private void runLoop() {
        long next=System.nanoTime();
        try {
            extension.onStart();
            while(running) {
                for(Player player:state.getPlayers()) player.beginTick();
                processCommands();
                for(int id:frozen) {Player p=state.getPlayer(id);if(p!=null)p.setMoving(false);}
                movement.update(state,periodNanos/1_000_000_000.0);
                // TV3 cập nhật timer, explosion, item sau movement và trước snapshot.
                extension.update(System.currentTimeMillis());
                tick++;publish();
                next+=periodNanos;long remaining=next-System.nanoTime();
                if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);else next=System.nanoTime();
            }
        } catch(InterruptedException e) {if(running){failure=e;Thread.currentThread().interrupt();}}
        catch(Throwable e) {failure=e;}
        finally {
            try { extension.onStop(); }
            catch(Throwable cleanupFailure) {
                if(failure==null) failure=cleanupFailure;
                else if(failure!=cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            finally { synchronized(this){running=false;commands.clear();} }
        }
    }
    private void processCommands() {
        Set<Integer> sampled=new HashSet<>();
        for(int i=0;i<256;i++) {
            GameCommand next=commands.peek();if(next==null)break;
            // Không đảo thứ tự toàn cục: mẫu thứ hai của cùng player chờ tick sau.
            // Contract FIFO ưu tiên thứ tự; handler/rate-limit TV4 cần chặn client spam.
            if(next.isMovement() && next.getSequence()>0 && sampled.contains(next.getPlayerId()))break;
            GameCommand command=commands.poll();processed++;
            if(command instanceof PlayerDisconnectedCommand disconnected) {
                frozen.add(command.getPlayerId());
                Player p=state.getPlayer(command.getPlayerId());if(p!=null)p.setMoving(false);
                if(disconnected.removePlayer())state.removePlayer(command.getPlayerId());
            } else if(command instanceof PlayerReconnectedCommand) {
                frozen.remove(command.getPlayerId());fullRequested.set(true);
            } else if(command.isMovement() && !frozen.contains(command.getPlayerId())) {
                if(movement.applyCommand(state,command) && command.getSequence()>0)sampled.add(command.getPlayerId());
            }
            // TV2 không xử lý PlantBombCommand; TV3 nhận qua interface này.
            extension.processCommand(command);
        }
    }
    private void publish() {
        boolean requested=fullRequested.getAndSet(false);
        boolean full=requested||tick==1||tick%100==0||state.getMatchState()==GameState.MatchState.FINISHED;
        GameStateUpdate update=builder.buildUpdate(state,tick,full);
        if(update==null)return;
        if(outbound.offer(update)){builder.commit(update);state.clearGameNotices();latest=update;}
        else {outboundFull++;if(full)fullRequested.set(true);}
    }
    /** Trả ảnh đã công bố gần nhất; không tạo snapshot trên network thread. Dùng queue để không bỏ delta. */
    public GameStateUpdate createUpdate() {return latest;}
    public GameStateUpdate takeOutboundUpdate() throws InterruptedException {return outbound.take();}
    public GameStateUpdate pollOutboundUpdate() {return outbound.poll();}
    public GameStateUpdate pollOutboundUpdate(long timeout,TimeUnit unit) throws InterruptedException {return outbound.poll(timeout,unit);}
    public void requestFullState() {fullRequested.set(true);}
    public int getPendingOutboundUpdateCount(){return outbound.size();}
    public int getPendingCommandCount(){return commands.size();}
    public long getRejectedCommandCount(){return rejectedFull.get();}
    public long getProcessedCommandCount(){return processed;}
    public long getOutboundQueueFullCount(){return outboundFull;}
    public long getTick(){return tick;}
    public Throwable getFailure(){return failure;}
    public boolean isRunning(){return running;}
    public synchronized boolean isThreadAlive(){return thread!=null&&thread.isAlive();}
    /** Không giữ monitor khi join, để finally của loop có thể hoàn tất cleanup. */
    public void stop() {
        Thread current;
        synchronized(this){running=false;current=thread;}
        if(current==null)return;
        current.interrupt();
        if(current!=Thread.currentThread()) {
            try {current.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Shutdown interrupted",e);}
            if(current.isAlive())throw new IllegalStateException("GameplayExtension did not stop within 2 seconds");
        }
    }
}

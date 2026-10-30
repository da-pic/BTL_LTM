package com.bombit.client.game.movement;

import com.bombit.server.game.movement.GameStateUpdate;
import java.util.*;

/** Nội suy player từ xa trên timeline tick server. Render lùi khoảng 2 tick (100 ms).
 * Dữ liệu delta phải được ghép vào replica trước khi đưa toàn bộ vị trí vào đây.
 * Không ngoại suy vô hạn khi mất mạng; ngoài vùng đệm thì giữ mẫu gần nhất.
 */
public final class InterpolationController {
    public record Position(double x, double y) { }
    private record Frame(long tick, Map<Integer, GameStateUpdate.PlayerData> players) { }
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Map<Integer,Integer> respawns=new HashMap<>();
    /** Gọi trên UI: không nội suy qua khoảng mất mạng hoặc bước nhảy vị trí khi phục hồi. */
    public void clear() { frames.clear();respawns.clear(); }
    /** Hide phải xóa mọi mẫu quá khứ; reveal sau đó không nội suy từ vị trí lần trước còn thấy. */
    public void forget(int playerId) {
        var kept=new ArrayDeque<Frame>();
        for(var frame:frames){var players=new HashMap<>(frame.players());players.remove(playerId);kept.add(new Frame(frame.tick(),Map.copyOf(players)));}
        frames.clear();frames.addAll(kept);respawns.remove(playerId);
    }
    public void record(ClientWorldState world) {
        if (!frames.isEmpty() && world.getTick() <= frames.getLast().tick()) return;
        if(!frames.isEmpty())for(int id:List.copyOf(frames.getLast().players().keySet()))if(!world.getPlayers().containsKey(id))forget(id);
        if(world.getMatchFrame()!=null){
            boolean teleported=false;
            for(var stats:world.getMatchFrame().players()){
                Integer previous=respawns.put(stats.playerId(),stats.respawns());
                if(previous!=null&&previous!=stats.respawns())teleported=true;
            }
            if(teleported)frames.clear();
        }
        frames.addLast(new Frame(world.getTick(), world.getPlayers()));
        while (frames.size() > 32) frames.removeFirst();
    }
    public Position sample(int playerId, double renderTick) {
        if (!Double.isFinite(renderTick)) throw new IllegalArgumentException("Invalid render tick");
        if (frames.isEmpty() || !frames.getLast().players().containsKey(playerId)) return null;
        Frame a = frames.getFirst(), b = a;
        for (Frame frame : frames) {
            b = frame; if (frame.tick() >= renderTick) break; a = frame;
        }
        var p = a.players().get(playerId); var q = b.players().get(playerId);
        if (q == null) return null;
        if (p == null || !p.isAlive() || !q.isAlive()) return new Position(q.getX(),q.getY());
        double alpha = b.tick() == a.tick() ? 1 : Math.max(0,Math.min(1,(renderTick-a.tick())/(b.tick()-a.tick())));
        return new Position(p.getX()+(q.getX()-p.getX())*alpha,p.getY()+(q.getY()-p.getY())*alpha);
    }
}

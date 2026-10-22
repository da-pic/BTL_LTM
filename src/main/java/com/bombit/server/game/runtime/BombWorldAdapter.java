package com.bombit.server.game.runtime;

import com.bombit.server.game.bomb.Bomb;
import com.bombit.server.game.bomb.BombRules;
import com.bombit.server.game.bomb.BombWorld;
import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.EntityData;
import java.util.*;
import java.util.function.LongSupplier;

/** Mutable bomb storage and projection into GameState, owned exclusively by one game thread. */
public final class BombWorldAdapter implements BombWorld {
    private final GameState state;
    private final Map<String,Integer> players;
    private final BombRules rules;
    private final LongSupplier nanoClock;
    private final Map<Integer,Bomb> bombs=new LinkedHashMap<>();
    private final Map<TilePosition,Integer> cells=new HashMap<>();
    private final Map<Integer,Long> plantedNanos=new HashMap<>();
    private Thread owner;

    public BombWorldAdapter(GameState state,Map<String,Integer> players,BombRules rules,LongSupplier nanoClock) {
        this.state=Objects.requireNonNull(state);this.players=Map.copyOf(players);
        this.rules=Objects.requireNonNull(rules);this.nanoClock=Objects.requireNonNull(nanoClock);
        if(new HashSet<>(players.values()).size()!=players.size()) throw new IllegalArgumentException("Duplicate player mapping");
    }
    public void bindToGameThread() {
        if(owner!=null) throw new IllegalStateException("World already bound");
        owner=Thread.currentThread();
    }
    private void checkThread() {
        if(owner!=Thread.currentThread()) throw new IllegalStateException("Bomb world accessed outside its game thread");
    }
    private Player player(String sessionId) {
        checkThread();Integer id=players.get(sessionId);return id==null?null:state.getPlayer(id);
    }
    @Override public boolean playerExists(String id) { return player(id)!=null; }
    @Override public boolean isPlayerAlive(String id) { Player p=player(id);return p!=null&&p.isAlive(); }
    @Override public TilePosition playerPosition(String id) {
        Player p=player(id);if(p==null) throw new IllegalArgumentException("Unknown player");return p.tilePosition(state.getMap());
    }
    @Override public int playerBombLimit(String id) { Player p=player(id);return p==null?rules.quota():p.getBombLimit(); }
    @Override public int playerBombRange(String id) { Player p=player(id);return p==null?rules.range():p.getBombRange(); }
    @Override public int activeBombCount(String id) {
        checkThread();return (int)bombs.values().stream().filter(b->b.ownerPlayerId().equals(id)).count();
    }
    @Override public TileType tileType(TilePosition p) { checkThread();return state.getMap().tileAt(p.x(),p.y()); }
    @Override public Optional<Bomb> bombAt(TilePosition p) { checkThread();return Optional.ofNullable(cells.get(p)).map(bombs::get); }
    @Override public Optional<Bomb> bombById(int id) { checkThread();return Optional.ofNullable(bombs.get(id)); }
    @Override public void addBomb(Bomb bomb) {
        checkThread();
        if(bombs.containsKey(bomb.id())||cells.containsKey(bomb.position())||tileType(bomb.position())!=TileType.EMPTY)
            throw new IllegalArgumentException("Invalid bomb cell or ID");
        long planted=nanoClock.getAsLong();
        state.putEntity(new EntityData(EntityData.Kind.BOMB,bomb.id(),bomb.position().x(),bomb.position().y(),
                bomb.ownerPlayerId(),bomb.range(),bomb.explodeAtMillis()));
        bombs.put(bomb.id(),bomb);cells.put(bomb.position(),bomb.id());plantedNanos.put(bomb.id(),planted);
    }
    @Override public void removeBomb(int id) {
        checkThread();Bomb bomb=bombs.remove(id);if(bomb==null) return;
        cells.remove(bomb.position());plantedNanos.remove(id);state.removeEntity("BOMB:"+id);
    }
    @Override public void destroyBlock(TilePosition p) {
        checkThread();if(tileType(p)==TileType.BREAKABLE_BLOCK) state.getMap().setTile(p.x(),p.y(),TileType.EMPTY);
    }
    @Override public Collection<String> alivePlayersAt(TilePosition p) {
        checkThread();double size=state.getMap().getTileSize(),x=p.x()*size,y=p.y()*size;
        return players.keySet().stream().filter(id->{Player v=player(id);return v!=null&&v.isAlive()
                &&v.getX()<x+size&&v.getX()+v.getWidth()>x&&v.getY()<y+size&&v.getY()+v.getHeight()>y;}).toList();
    }
    @Override public void killPlayer(String id,String killer) {
        Player p=player(id);if(p!=null) {p.setAlive(false);p.setMoving(false);}
    }
    @Override public void damagePlayer(String id,String killer){Player p=player(id);if(p!=null)p.takeDamage();}
    public List<Integer> dueBombIds(long nowNanos) {
        checkThread();return bombs.values().stream().filter(b->nowNanos-plantedNanos.get(b.id())>=b.fuseMillis()*1_000_000L)
                .map(Bomb::id).toList();
    }
    public void clear() {
        checkThread();for(Bomb b:List.copyOf(bombs.values())) {b.cancel();removeBomb(b.id());}
    }
}

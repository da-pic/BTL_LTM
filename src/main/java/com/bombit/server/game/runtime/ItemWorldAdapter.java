package com.bombit.server.game.runtime;

import com.bombit.server.game.core.*;
import com.bombit.server.game.item.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.EntityData;
import java.util.*;

/** Game-thread-owned items, projected into the same entity map as bombs. */
public final class ItemWorldAdapter implements ItemWorld {
    private final GameState state;
    private final Map<String,Integer> players;
    private final MatchRules rules;
    private final ItemEffectRegistry effects;
    private final Map<Integer,Item> items=new LinkedHashMap<>();
    private final Map<TilePosition,Integer> cells=new HashMap<>();
    private Thread owner;
    public ItemWorldAdapter(GameState state,Map<String,Integer> players,MatchRules rules){
        this(state,players,rules,ItemEffectRegistry.defaults());
    }
    public ItemWorldAdapter(GameState state,Map<String,Integer> players,MatchRules rules,ItemEffectRegistry effects){
        this.state=Objects.requireNonNull(state);this.players=Map.copyOf(players);this.rules=Objects.requireNonNull(rules);
        this.effects=Objects.requireNonNull(effects);
    }
    public void bindToGameThread(){if(owner!=null)throw new IllegalStateException("Already bound");owner=Thread.currentThread();}
    private void check(){if(owner!=Thread.currentThread())throw new IllegalStateException("Item world outside game thread");}
    private Player player(String id){check();Integer n=players.get(id);return n==null?null:state.getPlayer(n);}
    public boolean isPlayerAlive(String id){var p=player(id);return p!=null&&p.isAlive();}
    public TilePosition playerPosition(String id){var p=player(id);if(p==null)throw new IllegalArgumentException("Unknown player");return p.tilePosition(state.getMap());}
    public Optional<Item> itemAt(TilePosition p){check();return Optional.ofNullable(cells.get(p)).map(items::get);}
    public void addItem(Item item){
        check();var p=item.position();
        if(state.getMap().tileAt(p.x(),p.y())!=TileType.EMPTY||items.containsKey(item.id())||cells.containsKey(p))
            throw new IllegalArgumentException("Invalid item cell/ID");
        state.putEntity(new EntityData(EntityData.Kind.ITEM,item.id(),p.x(),p.y(),"",item.type().wireId(),0));
        items.put(item.id(),item);cells.put(p,item.id());
    }
    public void removeItem(int id){check();var item=items.remove(id);if(item!=null){item.remove();cells.remove(item.position());state.removeEntity("ITEM:"+id);}}
    public void applyItemEffect(String id,ItemType type){
        var p=player(id);if(p==null||!p.isAlive())return;
        effects.apply(type,p,rules);
    }
    public void clear(){check();for(int id:List.copyOf(items.keySet()))removeItem(id);}

    /** Chỉ lưu item đang tồn tại; không giữ Item mutable trong lịch sử. */
    public record SavedItem(int id, ItemType type, TilePosition position) {
        public SavedItem {
            if (id <= 0) throw new IllegalArgumentException("Invalid item ID");
            Objects.requireNonNull(type); Objects.requireNonNull(position);
        }
    }
    public List<SavedItem> captureCheckpoint() {
        check(); return items.values().stream().map(i -> new SavedItem(i.id(), i.type(), i.position())).toList();
    }
    public void validateRestore(List<SavedItem> saved) {
        check(); Objects.requireNonNull(saved);
        var ids = new HashSet<Integer>(); var positions = new HashSet<TilePosition>();
        for (var item : saved) {
            if (!ids.add(item.id()) || !positions.add(item.position())
                    || !state.getMap().isInsideMap(item.position().x(), item.position().y()))
                throw new IllegalArgumentException("Invalid item checkpoint");
        }
    }
    /** Coordinator khôi phục map trước, sau đó mới dựng lại index cell và entity projection. */
    public void restoreCheckpoint(List<SavedItem> saved) {
        validateRestore(saved);
        for (var item : saved) if (state.getMap().tileAt(item.position().x(), item.position().y()) != TileType.EMPTY)
            throw new IllegalArgumentException("Item checkpoint on blocked tile");
        clear();
        for (var item : saved) addItem(new Item(item.id(), item.type(), item.position()));
    }
}

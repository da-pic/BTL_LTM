package com.bombit.server.game.runtime;

import com.bombit.server.game.core.*;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.CollisionSystem;
import java.util.*;
import java.util.function.*;

/** Contract manager mới: mapFactory phải tạo map mới mỗi lần, extensionFactory tạo extension riêng từng room.
 * Callback factory chỉ giữ world cho implementation TV3; không chia sẻ GameState sang network.
 * synchronized tuần tự hóa vòng đời start/stop/close, còn submit dùng queue thread-safe của runtime.
 */
public final class GameRuntimeManager implements AutoCloseable {
    private final Map<String,GameRuntime> games=new HashMap<>();
    private final Function<String,GameMap> maps;
    @FunctionalInterface public interface ExtensionFactory {
        GameplayExtension create(String roomId, GameState state, Map<String,Integer> players);
    }
    private final ExtensionFactory extensions;
    private final Set<GameMap> usedMaps=Collections.newSetFromMap(new WeakHashMap<>());
    private boolean closed;
    public GameRuntimeManager(){this(id->defaultMap(),(id,state)->GameplayExtension.none());}
    public GameRuntimeManager(Function<String,GameMap> maps,BiFunction<String,GameState,GameplayExtension> extensions){
        this(maps, adapt(extensions));
    }
    private static ExtensionFactory adapt(BiFunction<String,GameState,GameplayExtension> extensions) {
        Objects.requireNonNull(extensions);
        return (room,state,players)->extensions.apply(room,state);
    }
    public GameRuntimeManager(Function<String,GameMap> maps,ExtensionFactory extensions){
        this.maps=Objects.requireNonNull(maps);this.extensions=Objects.requireNonNull(extensions);
    }
    public synchronized GameRuntime startGame(String roomId,Collection<PlayerSpawn> players){
        if(closed)throw new IllegalStateException("Manager closed");
        if(roomId==null||roomId.isBlank()||games.containsKey(roomId))throw new IllegalArgumentException("Invalid/duplicate room");
        List<PlayerSpawn> spawns=List.copyOf(players);
        if(spawns.isEmpty()||spawns.size()>4)throw new IllegalArgumentException("Match requires 1..4 players");
        GameMap map=Objects.requireNonNull(maps.apply(roomId));
        if(!usedMaps.add(map))throw new IllegalArgumentException("Map instance already used by another match");
        GameState state=new GameState(map);Map<String,Integer> ids=new LinkedHashMap<>();
        CollisionSystem collision=new CollisionSystem();
        for(PlayerSpawn spawn:spawns){
            Player p=new Player(spawn.playerId(),spawn.x(),spawn.y(),spawn.width(),spawn.height(),spawn.speed());
            if(ids.putIfAbsent(spawn.sessionId(),spawn.playerId())!=null||!state.addPlayer(p))throw new IllegalArgumentException("Duplicate player");
            if(!collision.canOccupy(map,p,p.getX(),p.getY()))throw new IllegalArgumentException("Spawn collides with map");
        }
        GameplayExtension extension=Objects.requireNonNull(extensions.create(roomId,state,Map.copyOf(ids)));
        state.startMatch();GameRuntime runtime=new GameRuntime(roomId,state,ids,extension);
        runtime.start();games.put(roomId,runtime);return runtime;
    }
    public synchronized Optional<GameRuntime> findGame(String roomId){return Optional.ofNullable(games.get(roomId));}
    public boolean submit(String roomId,GameCommand command){return findGame(roomId).map(r->r.submit(command)).orElse(false);}
    public synchronized void stopGame(String roomId){GameRuntime r=games.get(roomId);if(r!=null){r.close();games.remove(roomId);}}
    @Override public synchronized void close(){closed=true;for(String id:List.copyOf(games.keySet()))stopGame(id);}
    private static GameMap defaultMap(){
        GameMap map=new GameMap(17,15,32);
        for(int y=0;y<15;y++)for(int x=0;x<17;x++)if(x==0||y==0||x==16||y==14)map.setTile(x,y,TileType.HARD_WALL);
        return map;
    }
}

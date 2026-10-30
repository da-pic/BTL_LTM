package com.bombit.server.game.runtime;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.*;
import com.bombit.server.game.mode.*;
import com.bombit.server.game.model.TileType;
import com.bombit.server.game.movement.CollisionSystem;
import java.lang.ref.WeakReference;
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
    private final ModeRegistry registry;
    private final Set<GameMap> usedMaps=Collections.newSetFromMap(new WeakHashMap<>());
    private final List<WeakReference<GameplayExtension>> usedExtensions=new ArrayList<>();
    private boolean closed;
    private boolean framePipeline;
    public Optional<ModeRegistry> modeRegistry(){return Optional.ofNullable(registry);}
    /** Chỉ composition root v3 gọi trước trận đầu; không đổi pipeline của runtime đang chạy. */
    public synchronized void enableWorldFrames(){if(registry==null||!games.isEmpty()||closed)throw new IllegalStateException("Cannot switch pipeline");framePipeline=true;}
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
        this.registry=null;
    }
    /**
     * Đường mới có cấu hình/definition; constructor factory cũ vẫn giữ semantics legacy.
     * Chưa đổi pipeline snapshot wire v2: consumer v3/WorldFrame được ghép ở NV06/NV08.
     */
    public GameRuntimeManager(ModeRegistry registry){
        this.registry=Objects.requireNonNull(registry);this.maps=null;this.extensions=null;
    }
    public synchronized GameRuntime startGame(String roomId,Collection<PlayerSpawn> players){
        if(registry!=null)return startGame(roomId,players,registry.resolvePublic(GameMode.CLASSIC));
        return start(roomId,players,null);
    }
    /**
     * Cấu hình không được bỏ qua: manager legacy không biết rule trong closure phải từ chối.
     * PUBLIC qua registry; INTERNAL chỉ là đường fixture Classic do server cấp tường minh.
     */
    public synchronized GameRuntime startGame(String roomId,Collection<PlayerSpawn> players,MatchConfiguration configuration){
        if(registry==null)throw new ModeConfigurationException(ModeConfigurationException.Code.MODE_NOT_SUPPORTED);
        Objects.requireNonNull(configuration,"configuration");
        return start(roomId,players,configuration);
    }
    private GameRuntime start(String roomId,Collection<PlayerSpawn> players,MatchConfiguration configuration){
        if(closed)throw new IllegalStateException("Manager closed");
        if(roomId==null||roomId.isBlank()||games.containsKey(roomId))throw new IllegalArgumentException("Invalid/duplicate room");
        List<PlayerSpawn> spawns=List.copyOf(players);
        if(spawns.isEmpty()||spawns.size()>4)throw new IllegalArgumentException("Match requires 1..4 players");
        ModeDefinition mode=null;
        if(configuration!=null){
            if(configuration.profile()==MatchProfile.PUBLIC)registry.validatePublicStart(configuration,spawns.size());
            else registry.validateInternalStart(configuration,spawns.size());
            validateConfiguredSpawns(spawns,configuration.effectiveRules().common());
            mode=registry.find(configuration.gameMode()).orElseThrow();
        }
        GameMap map=Objects.requireNonNull(mode==null?maps.apply(roomId):mode.createMap(roomId,configuration));
        if(configuration!=null){
            var rules=configuration.effectiveRules().common();
            if(map.getWidthInTiles()!=rules.width()||map.getHeightInTiles()!=rules.height()||map.getTileSize()!=rules.tilePixels())
                throw new IllegalArgumentException("Factory map differs from match configuration");
        }
        if(!usedMaps.add(map))throw new IllegalArgumentException("Map instance already used by another match");
        GameState state=new GameState(map);Map<String,Integer> ids=new LinkedHashMap<>();
        CollisionSystem collision=new CollisionSystem();
        for(PlayerSpawn spawn:spawns){
            Player p=new Player(spawn.playerId(),spawn.x(),spawn.y(),spawn.width(),spawn.height(),spawn.speed());
            if(ids.putIfAbsent(spawn.sessionId(),spawn.playerId())!=null||!state.addPlayer(p))throw new IllegalArgumentException("Duplicate player");
            if(!collision.canOccupy(map,p,p.getX(),p.getY()))throw new IllegalArgumentException("Spawn collides with map");
        }
        GameplayExtension extension=Objects.requireNonNull(mode==null?extensions.create(roomId,state,Map.copyOf(ids))
                :mode.createGameplay(roomId,configuration,state,ids));
        if(configuration!=null)reserveExtension(extension);
        state.startMatch();GameRuntime runtime=new GameRuntime(roomId,state,ids,extension,configuration,framePipeline,
                mode==null?System::nanoTime:mode.nanoClock(),mode==null?System::currentTimeMillis:mode.epochClock());
        runtime.start();games.put(roomId,runtime);return runtime;
    }
    /** Không để caller khai báo stats khác metadata; vị trí thật còn được collision kiểm tra. */
    private static void validateConfiguredSpawns(List<PlayerSpawn> spawns,EffectiveRules.Common rules){
        if(rules.tickTargetMillis()!=rules.inputStepMillis())
            throw new IllegalArgumentException("This movement runtime requires matching tick/input steps");
        var sessions=new HashSet<String>();var ids=new HashSet<Integer>();
        for(var spawn:spawns){
            if(!sessions.add(spawn.sessionId())||!ids.add(spawn.playerId()))throw new IllegalArgumentException("Duplicate player");
            if(Double.compare(spawn.width(),rules.hitboxPixels())!=0||Double.compare(spawn.height(),rules.hitboxPixels())!=0
                    ||Double.compare(spawn.speed(),rules.initialSpeed())!=0)
                throw new IllegalArgumentException("Spawn stats differ from match configuration");
        }
    }
    /** So reference identity, không gọi equals của extension; weak refs không giữ cả world sau stop. */
    private void reserveExtension(GameplayExtension extension){
        for(var iterator=usedExtensions.iterator();iterator.hasNext();){
            var previous=iterator.next().get();
            if(previous==null)iterator.remove();
            else if(previous==extension)throw new IllegalArgumentException("Extension instance already used by another match");
        }
        usedExtensions.add(new WeakReference<>(extension));
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

package com.bombit.server.game.map;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.Direction;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Decides where hard walls and breakable obstacles are placed for each match. */
public final class ArenaGenerator {
    public static final int WIDTH=17,HEIGHT=15,TILE_SIZE=32;
    public static final List<TilePosition> SPAWNS=List.of(
            new TilePosition(1,1),new TilePosition(15,1),new TilePosition(1,13),new TilePosition(15,13));
    private static final Set<TilePosition> SAFE_CELLS=safeCells();
    private ArenaGenerator(){ }

    /** Generates a different connected arena for every match. */
    public static GameMap create(){return create(ThreadLocalRandom.current().nextLong());}

    /** Generates a reproducible arena for tests and previews. */
    public static GameMap create(long seed){
        SplittableRandom random=new SplittableRandom(seed);
        for(int attempt=0;attempt<300;attempt++){
            GameMap map=emptyArena();
            List<TilePosition> candidates=interiorCells();Collections.shuffle(candidates,new Random(random.nextLong()));
            int hardTarget=18+random.nextInt(13),hard=0;
            for(TilePosition cell:candidates){
                if(hard>=hardTarget)break;
                if(SAFE_CELLS.contains(cell))continue;
                map.setTile(cell.x(),cell.y(),TileType.HARD_WALL);
                if(spawnsConnected(map))hard++;else map.setTile(cell.x(),cell.y(),TileType.EMPTY);
            }
            if(hard<hardTarget)continue;

            List<TilePosition> crateCandidates=interiorCells();Collections.shuffle(crateCandidates,new Random(random.nextLong()));
            int crateTarget=38+random.nextInt(19),crates=0;
            for(TilePosition cell:crateCandidates){
                if(crates>=crateTarget)break;
                if(SAFE_CELLS.contains(cell)||map.tileAt(cell.x(),cell.y())!=TileType.EMPTY)continue;
                map.setTile(cell.x(),cell.y(),TileType.BREAKABLE_BLOCK);
                if(spawnsConnected(map))crates++;else map.setTile(cell.x(),cell.y(),TileType.EMPTY);
            }
            if(crates>=34&&spawnsConnected(map))return map;
        }
        throw new IllegalStateException("Unable to generate a connected arena");
    }

    private static GameMap emptyArena(){
        GameMap map=new GameMap(WIDTH,HEIGHT,TILE_SIZE);
        for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++)
            map.setTile(x,y,x==0||y==0||x==WIDTH-1||y==HEIGHT-1?TileType.HARD_WALL:TileType.EMPTY);
        return map;
    }
    private static List<TilePosition> interiorCells(){
        List<TilePosition> cells=new ArrayList<>();
        for(int y=1;y<HEIGHT-1;y++)for(int x=1;x<WIDTH-1;x++)cells.add(new TilePosition(x,y));
        return cells;
    }
    private static Set<TilePosition> safeCells(){
        Set<TilePosition> safe=new HashSet<>();
        for(TilePosition spawn:SPAWNS){int dx=spawn.x()<WIDTH/2?1:-1,dy=spawn.y()<HEIGHT/2?1:-1;
            for(int step=0;step<=2;step++){safe.add(new TilePosition(spawn.x()+dx*step,spawn.y()));safe.add(new TilePosition(spawn.x(),spawn.y()+dy*step));}}
        return Set.copyOf(safe);
    }
    private static boolean spawnsConnected(GameMap map){
        Set<TilePosition> reached=new HashSet<>();ArrayDeque<TilePosition> queue=new ArrayDeque<>();TilePosition first=SPAWNS.getFirst();reached.add(first);queue.add(first);
        while(!queue.isEmpty()){
            TilePosition current=queue.removeFirst();
            for(Direction direction:Direction.values()){
                TilePosition next=current.move(direction,1);
                if(map.tileAt(next.x(),next.y())==TileType.EMPTY&&reached.add(next))queue.addLast(next);
            }
        }
        return reached.containsAll(SPAWNS);
    }
}

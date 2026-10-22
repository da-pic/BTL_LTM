package com.bombit.server.game.map;

import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;
import java.util.List;

/** Gameplay arena. Edit this layout: # permanent stone, B breakable crate, . walkable floor. */
public final class DefaultArenaMap {
    public static final int WIDTH=17,HEIGHT=15,TILE_SIZE=32;
    public static final List<TilePosition> SPAWNS=List.of(
        new TilePosition(1,1),new TilePosition(15,1),new TilePosition(1,13),new TilePosition(15,13));
    private static final List<String> LAYOUT=List.of(
        "#################",
        "#...............#",
        "#.#.#.#.#.#.#.#.#",
        "#.BBBB.....BBBB.#",
        "#.#B#B#.#.#B#B#.#",
        "#....BB.B.BB....#",
        "#.#B#.#.#.#.#B#.#",
        "#...............#",
        "#.#B#.#.#.#.#B#.#",
        "#....BB.B.BB....#",
        "#.#B#B#.#.#B#B#.#",
        "#.BBBB.....BBBB.#",
        "#.#.#.#.#.#.#.#.#",
        "#...............#",
        "#################");
    private DefaultArenaMap(){}
    public static GameMap create(){
        if(LAYOUT.size()!=HEIGHT)throw new IllegalStateException("Invalid arena height");
        var map=new GameMap(WIDTH,HEIGHT,TILE_SIZE);
        for(int y=0;y<HEIGHT;y++){
            if(LAYOUT.get(y).length()!=WIDTH)throw new IllegalStateException("Invalid arena row: "+y);
            for(int x=0;x<WIDTH;x++)map.setTile(x,y,switch(LAYOUT.get(y).charAt(x)){
                case '#' -> TileType.HARD_WALL;
                case 'B' -> TileType.BREAKABLE_BLOCK;
                case '.' -> TileType.EMPTY;
                default -> throw new IllegalStateException("Unknown arena tile");
            });
        }
        return map;
    }
}

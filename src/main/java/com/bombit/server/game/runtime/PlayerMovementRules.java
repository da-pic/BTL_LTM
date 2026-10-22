package com.bombit.server.game.runtime;

import com.bombit.server.game.model.TilePosition;

/** Server-owned movement balance; spawn is centered without changing the map's grid geometry. */
public record PlayerMovementRules(double hitboxSize,double initialSpeed) {
    public PlayerMovementRules {
        if(!Double.isFinite(hitboxSize)||hitboxSize<=0||!Double.isFinite(initialSpeed)||initialSpeed<=0)
            throw new IllegalArgumentException("Invalid player movement rules");
    }
    public static PlayerMovementRules defaults(){
        return new PlayerMovementRules(Double.parseDouble(System.getProperty("bombit.player.hitboxSize","24")),
            Double.parseDouble(System.getProperty("bombit.player.initialSpeed","80")));
    }
    public PlayerSpawn spawn(String session,int playerId,TilePosition cell,int tileSize){
        if(tileSize<=0||hitboxSize>=tileSize)throw new IllegalArgumentException("Hitbox must be smaller than a tile");
        double inset=(tileSize-hitboxSize)/2;
        return new PlayerSpawn(session,playerId,cell.x()*tileSize+inset,cell.y()*tileSize+inset,hitboxSize,hitboxSize,initialSpeed);
    }
}

package com.bombit.server.game.item;

public enum ItemType {
    BOMB_UP(0), FIRE_UP(1), SPEED_UP(2), LIFE_UP(3), SHIELD(4);
    private final int wireId;
    ItemType(int wireId){this.wireId=wireId;}
    public int wireId(){return wireId;}
    public static ItemType fromWireId(int id){
        for(var type:values())if(type.wireId==id)return type;
        throw new IllegalArgumentException("Unknown item type: "+id);
    }
}

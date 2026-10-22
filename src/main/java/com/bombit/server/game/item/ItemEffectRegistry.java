package com.bombit.server.game.item;

import com.bombit.server.game.core.Player;
import com.bombit.server.game.runtime.MatchRules;
import java.util.*;
import java.util.function.BiConsumer;

/** Immutable effect catalog: new types register effects without changing world/pickup control flow. */
public final class ItemEffectRegistry {
    private final Map<ItemType,BiConsumer<Player,MatchRules>> effects;
    public ItemEffectRegistry(Map<ItemType,BiConsumer<Player,MatchRules>> effects){
        this.effects=Map.copyOf(effects);
        if(!this.effects.keySet().containsAll(EnumSet.allOf(ItemType.class)))throw new IllegalArgumentException("Missing item effect");
    }
    public void apply(ItemType type,Player player,MatchRules rules){effects.get(Objects.requireNonNull(type)).accept(player,rules);}
    public static ItemEffectRegistry defaults(){
        return new ItemEffectRegistry(Map.of(
            ItemType.BOMB_UP,(p,r)->p.setBombLimit(p.getBombLimit()<r.maxBombs()?p.getBombLimit()+1:p.getBombLimit()),
            ItemType.FIRE_UP,(p,r)->p.setBombRange(p.getBombRange()<r.maxRange()?p.getBombRange()+1:p.getBombRange()),
            ItemType.SPEED_UP,(p,r)->p.setSpeed(Math.min(r.maxSpeed(),p.getSpeed()+r.speedIncrement())),
            ItemType.LIFE_UP,(p,r)->p.setLives(p.getLives()<r.maxLives()?p.getLives()+1:p.getLives()),
            ItemType.SHIELD,(p,r)->p.setShield(true)));
    }
}

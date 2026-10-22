package com.bombit.server.game.bomb;

import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.model.TileType;

import java.util.Collection;
import java.util.Optional;

/**
 * Ranh giới tích hợp do bộ chuyển đổi GameState của TV2 triển khai.
 * Mã xử lý bom chỉ thay đổi thế giới thông qua phần triển khai thuộc quyền quản lý của Game Loop.
 */
public interface BombWorld {
    boolean playerExists(String playerId);

    boolean isPlayerAlive(String playerId);

    TilePosition playerPosition(String playerId);

    int playerBombLimit(String playerId);

    int playerBombRange(String playerId);

    int activeBombCount(String playerId);

    TileType tileType(TilePosition position);

    Optional<Bomb> bombAt(TilePosition position);

    Optional<Bomb> bombById(int bombId);

    void addBomb(Bomb bomb);

    void removeBomb(int bombId);

    void destroyBlock(TilePosition position);

    Collection<String> alivePlayersAt(TilePosition position);

    void killPlayer(String playerId, String killerPlayerId);
    /** Damage policy may absorb a hit or respawn; elimination is checked after this call. */
    default void damagePlayer(String playerId,String killerPlayerId){killPlayer(playerId,killerPlayerId);}
}

package com.bombit.client.game.bomb;

import com.bombit.server.game.model.TilePosition;

public record BombView(int bombId, TilePosition position, long explodeAtMillis, int range) {
}

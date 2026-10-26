package com.bombit.client.ui.game.bomb;

import com.bombit.server.game.model.TilePosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombPresentationStateTest {
    @Test
    void replacesBombWithTemporaryExplosion() {
        BombPresentationState state = new BombPresentationState();
        state.bombPlaced(1, new TilePosition(2, 2), 3_000, 2);

        state.bombExploded(1, List.of(new TilePosition(2, 2)), 3_000, 500);

        assertTrue(state.bombs().isEmpty());
        assertEquals(1, state.explosions().size());
        state.removeExpiredExplosions(3_500);
        assertTrue(state.explosions().isEmpty());
    }
}

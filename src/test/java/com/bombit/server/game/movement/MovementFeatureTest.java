package com.bombit.server.game.movement;

import org.junit.jupiter.api.Test;

/** Cho Maven chạy cùng kịch bản với script CLI; lỗi AssertionError làm test thất bại. */
final class MovementFeatureTest {
    @Test void movementFeaturesAndTcp() throws Exception { MovementFeatureCheck.main(new String[0]); }
}

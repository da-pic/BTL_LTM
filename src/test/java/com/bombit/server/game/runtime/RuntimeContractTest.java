package com.bombit.server.game.runtime;

import org.junit.jupiter.api.Test;

/** Maven chạy cùng bộ hồi quy contract với CLI để không có hai bộ tiêu chí khác nhau. */
final class RuntimeContractTest {
    @Test void runtimeContract()throws Exception{RuntimeContractCheck.main(new String[0]);}
}

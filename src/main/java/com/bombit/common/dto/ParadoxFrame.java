package com.bombit.common.dto;

import java.util.*;

/** State công khai bất biến; cooldown dùng thời gian runtime, simulationTick được phép lùi. */
public record ParadoxFrame(long timelineEpoch,long simulationTick,Set<Integer> availableCharges,long cooldownRemainingMillis) {
    public ParadoxFrame {
        availableCharges=Set.copyOf(availableCharges);
        if(timelineEpoch<0||simulationTick<0||cooldownRemainingMillis<0||cooldownRemainingMillis>3000
                ||availableCharges.size()>4||availableCharges.stream().anyMatch(id->id<=0))throw new IllegalArgumentException("Invalid Paradox frame");
    }
}

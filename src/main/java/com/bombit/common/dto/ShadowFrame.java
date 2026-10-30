package com.bombit.common.dto;

import java.util.List;
import java.util.Objects;

/** Ảnh bất biến của gameplay Shadow; codec/replica sẽ truyền riêng, không đổi schema Classic. */
public record ShadowFrame(long tick, int remainingSeconds, int scoreA, int scoreB,
                          List<Station> stations, Result result) {
    public enum Reason { BOTH_TEAMS_ELIMINATED, TEAM_ELIMINATED, TARGET_SCORE, TIMEOUT, ABORTED }

    /** Null winningTeam nghĩa là hòa/hủy, không phải một player ID đặc biệt. */
    public record Result(long tick, TeamId winningTeam, Reason reason, int scoreA, int scoreB) {
        public Result {
            Objects.requireNonNull(reason);
            if (tick < 0 || scoreA < 0 || scoreB < 0
                    || (reason == Reason.BOTH_TEAMS_ELIMINATED || reason == Reason.ABORTED) && winningTeam != null
                    || reason == Reason.TEAM_ELIMINATED && winningTeam == null) {
                throw new IllegalArgumentException("Invalid Shadow result");
            }
        }
    }

    public record Station(int id, int tileX, int tileY, TeamId owner, TeamId challenger,
                          int captureProgress, int scoreProgress, boolean contested) {
        public Station {
            if (id < 1 || id > 3 || tileX < 0 || tileY < 0 || captureProgress < 0 || captureProgress >= 40
                    || scoreProgress < 0 || scoreProgress >= 20
                    || (challenger == null) != (captureProgress == 0)
                    || challenger != null && challenger == owner || contested && challenger != null
                    || owner == null && scoreProgress != 0) {
                throw new IllegalArgumentException("Invalid Shadow station");
            }
        }
    }

    public ShadowFrame {
        stations = List.copyOf(stations);
        if (tick < 0 || remainingSeconds < 0 || scoreA < 0 || scoreB < 0 || stations.size() != 3
                || result != null && (result.tick() != tick || result.scoreA() != scoreA || result.scoreB() != scoreB)) {
            throw new IllegalArgumentException("Invalid Shadow frame");
        }
        for (int i = 0; i < stations.size(); i++)
            if (stations.get(i).id() != i + 1) throw new IllegalArgumentException("Invalid station order");
    }
}

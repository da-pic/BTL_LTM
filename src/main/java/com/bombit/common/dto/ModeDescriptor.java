package com.bombit.common.dto;

import java.util.Objects;

/**
 * Metadata công khai cho lobby tương lai, không có factory/state/secret của server.
 * Null chỉ dành cho luật/số người/feature của mode tương lai chưa được định nghĩa.
 * Một mode chưa triển khai không được quảng bá capability hoặc tạo cấu hình chơi giả.
 */
public record ModeDescriptor(GameMode gameMode, String rulesVersion, boolean available,
                             UnavailableReason unavailableReason, Integer minPlayers, Integer maxPlayers,
                             Integer teamSize, String requiredFeature, ModeCapabilities capabilities) {
    public enum UnavailableReason { NOT_IMPLEMENTED, DISABLED }

    public ModeDescriptor {
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(capabilities, "capabilities");
        if (available != (unavailableReason == null)) {
            throw new IllegalArgumentException("Availability and reason disagree");
        }
        boolean hasLimits = minPlayers != null && maxPlayers != null && teamSize != null;
        if (!hasLimits && (minPlayers != null || maxPlayers != null || teamSize != null)) {
            throw new IllegalArgumentException("Player limits must be all present or all absent");
        }
        if (hasLimits && (minPlayers < 1 || maxPlayers > 4 || minPlayers > maxPlayers
                || teamSize < 0 || teamSize > maxPlayers || (teamSize > 0 && maxPlayers != 2 * teamSize))) {
            throw new IllegalArgumentException("Invalid mode player limits");
        }
        if (rulesVersion != null && rulesVersion.isBlank()) throw new IllegalArgumentException("Empty rules version");
        if (requiredFeature != null && !requiredFeature.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid feature name");
        }
        if ((available || unavailableReason == UnavailableReason.DISABLED)
                && (!hasLimits || rulesVersion == null || requiredFeature == null)) {
            throw new IllegalArgumentException("Implemented modes require complete metadata");
        }
        if (unavailableReason == UnavailableReason.NOT_IMPLEMENTED && !capabilities.equals(ModeCapabilities.NONE)) {
            throw new IllegalArgumentException("Unimplemented modes cannot advertise capabilities");
        }
    }
}

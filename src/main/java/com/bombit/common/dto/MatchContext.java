package com.bombit.common.dto;

import com.bombit.common.protocol.IntegrationContract;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/** Immutable mapping published in GAME_STARTED. Never accepts an identity supplied by MOVE. */
public record MatchContext(String roomId, UUID matchId, List<PlayerIdentity> players) {
    public record PlayerIdentity(String sessionId, int playerId) {
        public PlayerIdentity {
            if (sessionId == null || sessionId.isBlank() || playerId <= 0) {
                throw new IllegalArgumentException("Invalid player identity");
            }
        }
    }

    public MatchContext {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("Missing room");
        Objects.requireNonNull(matchId, "matchId");
        players = List.copyOf(players);
        if (players.isEmpty() || players.size() > IntegrationContract.MAX_PLAYERS) {
            throw new IllegalArgumentException("Match requires 1..4 players");
        }
        var sessions = new HashSet<String>();
        var ids = new HashSet<Integer>();
        for (var player : players) {
            if (!sessions.add(player.sessionId()) || !ids.add(player.playerId())) {
                throw new IllegalArgumentException("Duplicate player mapping");
            }
        }
    }

    public OptionalInt playerId(String sessionId) {
        return players.stream().filter(p -> p.sessionId().equals(sessionId))
                .mapToInt(PlayerIdentity::playerId).findFirst();
    }
}

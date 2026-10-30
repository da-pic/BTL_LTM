package com.bombit.server.game.mode.shadow;

import com.bombit.common.dto.*;
import com.bombit.server.game.core.GameMap;
import com.bombit.server.game.runtime.PlayerMovementRules;
import com.bombit.server.game.runtime.PlayerSpawn;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Đầu vào Shadow đã chốt từ ảnh phòng WAITING: luật, roster, spawn và metadata.
 * Chưa chạy loop hoặc dùng gameplay Classic thay cho Shadow; registry công khai vẫn khóa mode.
 */
public final class ShadowMatchSetup {
    private final String roomId;
    private final MatchConfiguration configuration;
    private final long configurationRevision;
    private final ShadowRoster roster;
    private final List<PlayerSpawn> spawns;

    private ShadowMatchSetup(RoomStateView room) {
        roomId = room.roomId();
        configuration = room.configuration();
        configurationRevision = room.configurationRevision();
        roster = ShadowRoster.fromLobby(room.players());
        var rules = configuration.effectiveRules().common();
        var movement = new PlayerMovementRules(rules.hitboxPixels(), rules.initialSpeed());
        spawns = roster.players().stream().map(p -> movement.spawn(p.sessionId(), p.playerId(),
                ShadowArena.spawn(p.slot()), rules.tilePixels())).toList();
    }

    /** Cấu hình server dùng để chuẩn bị Shadow; có DTO không đồng nghĩa được phép start qua lobby live. */
    public static MatchConfiguration newConfiguration() {
        return new MatchConfiguration(UUID.randomUUID(), GameMode.SHADOW_RELAY, "shadow-relay-v1",
                MatchPreset.SHADOW_RELAY_V1, MatchProfile.PUBLIC, ShadowArena.LAYOUT_ID, EffectiveRules.ShadowRelay.v1());
    }

    /** Fail trước khi tạo map/thread nếu thiếu người, sai mode hoặc READY chưa xác nhận roster hiện hành. */
    public static ShadowMatchSetup prepare(RoomStateView room) {
        Objects.requireNonNull(room, "room");
        if (!room.state().equals("WAITING") || room.configuration().gameMode() != GameMode.SHADOW_RELAY
                || room.configuration().presetId() != MatchPreset.SHADOW_RELAY_V1
                || !room.configuration().effectiveRules().equals(EffectiveRules.ShadowRelay.v1())
                || room.maxPlayers() != 4 || room.players().size() != 4) {
            throw new IllegalArgumentException("Shadow requires a waiting room with four players and v1 rules");
        }
        for (var player : room.players()) {
            boolean host = player.sessionId().equals(room.hostId());
            if (host ? !List.of("IN_ROOM", "READY").contains(player.state())
                    : !player.state().equals("READY") || !Long.valueOf(room.configurationRevision()).equals(player.readyRevision())) {
                throw new IllegalStateException("Shadow roster is not ready");
            }
        }
        return new ShadowMatchSetup(room);
    }

    public String roomId() { return roomId; }
    public MatchConfiguration configuration() { return configuration; }
    public long configurationRevision() { return configurationRevision; }
    public ShadowRoster roster() { return roster; }
    public List<PlayerSpawn> spawns() { return spawns; }
    public GameMap createMap() { return ShadowArena.create(); }
    /** Ghép vào GameLoop chung; luật đội nằm trong extension Shadow, không bật mode trên lobby ở đây. */
    public ShadowGameplayExtension createGameplay(com.bombit.server.game.core.GameState state) {
        return new ShadowGameplayExtension(state, roster, (EffectiveRules.ShadowRelay) configuration.effectiveRules());
    }

    /** Match ID do runtime cấp; theme do lobby resolve một lần. Cả hai không làm đổi đội hoặc luật. */
    public MatchStartMetadata metadata(UUID matchId, MapTheme theme, int recipientPlayerId) {
        roster.player(recipientPlayerId);
        return new MatchStartMetadata(new MatchContext(roomId, matchId, theme, roster.identities()),
                configuration, configurationRevision, roster.players(), new StreamContext(recipientPlayerId, 1, 0, 0));
    }
}

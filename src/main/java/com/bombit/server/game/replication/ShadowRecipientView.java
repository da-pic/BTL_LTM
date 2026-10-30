package com.bombit.server.game.replication;

import com.bombit.common.dto.*;
import com.bombit.server.game.model.TilePosition;
import com.bombit.server.game.movement.GameStateUpdate;
import java.util.List;
import java.util.Set;

/** View đã loại dữ liệu bí mật, chưa phải packet/delta. Không giữ tham chiếu world đầy đủ. */
public record ShadowRecipientView(int recipientPlayerId,TeamId team,GameStateUpdate snapshot,
                                  ShadowFrame objectives,ShadowScanFrame scans,Set<TilePosition> visibleCells,
                                  List<PublicPlayer> publicPlayers,long lastProcessedInput,long scanReadyAtMillis) {
    public record PublicPlayer(int playerId,TeamId team,boolean alive,boolean frozen,boolean forfeited) { }
    public ShadowRecipientView {visibleCells=Set.copyOf(visibleCells);publicPlayers=List.copyOf(publicPlayers);}
}

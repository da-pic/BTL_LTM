package com.bombit.server.game.movement;

import com.bombit.common.protocol.Packet;
import com.bombit.server.dispatch.MessageHandler;
import com.bombit.server.game.core.*;
import com.bombit.server.game.runtime.GameRuntimeManager;
import com.bombit.server.session.*;
import java.util.Objects;
import java.util.function.Function;

/** Router TV4 đăng ký handler cho MOVE. Resolver phải tìm session theo clientId tin cậy.
 * Từ chối bằng exception để tầng tích hợp trả ERROR; không sửa state từ thread socket.
 */
public final class MovementHandler implements MessageHandler {
    private final GameRuntimeManager gameRuntimes;
    private final Function<String, Session> sessions;
    /** Constructor contract mới; resolver vẫn thuộc tầng tích hợp TV1/TV4. */
    public MovementHandler(GameRuntimeManager runtimes, Function<String, Session> sessions) {
        this.gameRuntimes = Objects.requireNonNull(runtimes); this.sessions = Objects.requireNonNull(sessions);
    }
    @Override public void handle(String clientId, Packet packet) {
        Session session = sessions.apply(clientId);
        if (session == null || session.getState() != SessionState.PLAYING) throw new IllegalStateException("Not playing");
        var runtime = gameRuntimes.findGame(session.getRoomId()).orElseThrow(() -> new IllegalStateException("Match missing"));
        int id = runtime.playerId(session.getPlayerId()).orElseThrow(() -> new IllegalStateException("Player not in match"));
        var input = MovePacketCodec.decode(packet);
        var direction = input.direction();
        GameCommand command = input.moving() ? new MoveCommand(id,direction,input.sequence())
                : new StopMovingCommand(id,direction,input.sequence());
        if (!runtime.submit(command)) throw new IllegalStateException("Input queue unavailable");
    }
}

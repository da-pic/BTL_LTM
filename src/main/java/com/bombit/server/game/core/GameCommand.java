package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;
import java.util.Objects;

/** Command gameplay bất biến. Constructor cũ chỉ giữ tương thích client/demo hiện có.
 * Code mới dùng MoveCommand, StopMovingCommand, PlantBombCommand và command lifecycle.
 * Chọn sealed class thay cho sealed interface để không buộc sửa client ngoài phạm vi TV2.
 * Mọi field/getter final; các lớp con final không thể thay đổi payload sau enqueue.
 */
public sealed class GameCommand permits MoveCommand, StopMovingCommand, PlantBombCommand,
        PlayerDisconnectedCommand, PlayerReconnectedCommand, RewindCommand {
    private final int playerId;
    private final Direction direction;
    private final boolean moving;
    private final long sequence;
    protected GameCommand(int playerId,Direction direction,boolean moving,long sequence) {
        if(playerId<=0 || sequence<0) throw new IllegalArgumentException("Invalid input");
        this.playerId=playerId;this.direction=Objects.requireNonNull(direction);
        this.moving=moving;this.sequence=sequence;
    }
    @Deprecated public GameCommand(int id,Player.Direction direction,boolean moving,long sequence) {
        this(id,Direction.valueOf(Objects.requireNonNull(direction).name()),moving,sequence);
    }
    @Deprecated public GameCommand(int id,Player.Direction direction,boolean moving) { this(id,direction,moving,0); }
    public final int getPlayerId() { return playerId; }
    public final Direction direction() { return direction; }
    @Deprecated public final Player.Direction getDirection() { return Player.Direction.valueOf(direction.name()); }
    public final boolean isMoving() { return moving; }
    public final long getSequence() { return sequence; }
    public final boolean isMovement() {
        return getClass()==GameCommand.class || this instanceof MoveCommand || this instanceof StopMovingCommand;
    }
    @Override public String toString() { return getClass().getSimpleName()+"[player="+playerId+",seq="+sequence+"]"; }
}

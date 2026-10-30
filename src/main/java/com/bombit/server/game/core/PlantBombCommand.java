package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** TV2 chỉ khai báo ý định; GameplayExtension TV3 xác thực và xử lý luật đặt bom.
 * Không nhận tọa độ client: TV3 đọc vị trí thật từ world của phòng.
 */
public final class PlantBombCommand extends GameCommand {
    private final long requestId;
    private final com.bombit.common.dto.BombType bombType;
    public PlantBombCommand(int playerId) { this(playerId,0,com.bombit.common.dto.BombType.NORMAL); }
    public PlantBombCommand(int playerId,long requestId,com.bombit.common.dto.BombType bombType){
        super(playerId,Direction.DOWN,false,0);if(requestId<0)throw new IllegalArgumentException("Invalid request");
        this.requestId=requestId;this.bombType=java.util.Objects.requireNonNull(bombType);
    }
    public long requestId(){return requestId;}
    public com.bombit.common.dto.BombType bombType(){return bombType;}
}

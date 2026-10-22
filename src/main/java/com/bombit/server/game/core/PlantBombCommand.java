package com.bombit.server.game.core;

import com.bombit.server.game.model.Direction;

/** TV2 chỉ khai báo ý định; GameplayExtension TV3 xác thực và xử lý luật đặt bom.
 * Không nhận tọa độ client: TV3 đọc vị trí thật từ world của phòng.
 */
public final class PlantBombCommand extends GameCommand {
    public PlantBombCommand(int playerId) { super(playerId,Direction.DOWN,false,0); }
}

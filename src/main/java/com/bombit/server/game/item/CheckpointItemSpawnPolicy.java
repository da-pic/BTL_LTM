package com.bombit.server.game.item;

import com.bombit.server.game.checkpoint.CheckpointParticipant;

/** Policy opt-in cho Paradox: RNG phải xuất/nhập được trạng thái, không dùng Random ẩn. */
public interface CheckpointItemSpawnPolicy extends ItemSpawnPolicy, CheckpointParticipant<Long> { }

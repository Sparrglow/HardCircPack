package com.sparrglow.hardcircpack.menu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 打开总控台时服务端同步给客户端的开屏数据。
 * 只携带方块坐标，所有业务状态通过 {@code StatePayload} 推送。
 */
public record ControlHubOpenData(BlockPos pos) {

    public static final StreamCodec<RegistryFriendlyByteBuf, ControlHubOpenData> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, ControlHubOpenData::pos, ControlHubOpenData::new);
}

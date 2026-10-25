package com.sparrglow.hardcircpack.transceiver;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 收发器方块实体。阶段1只做数据搬运：
 * onDataReceived 存待应用端子值，tick 中与 BlockState 对比后按需写红石。
 * <p>
 * 不持有传输实例、不调用 pollReceived()，所有数据由 ServerTransportManager 路由投递。
 */
public class HardCircTransceiverBlockEntity extends BlockEntity implements Transceiver {

    private static final String NBT_TRANSCEIVER_ID = "transceiver_id";
    private static final int MIN_ID = 1;
    private static final int MAX_ID = 255;
    private static final int MIN_POWER = 0;
    private static final int MAX_POWER = 15;

    /** 协议帧中的收发器 ID，0 表示尚未分配。 */
    private int transceiverId;
    /** 已成功写入 TransceiverRegistry。 */
    private boolean registered;
    /** ID 分配失败（1–255 耗尽）后不再每 tick 重试与刷日志。 */
    private boolean registrationFailed;
    private boolean tickLogged;

    private int pendingTerminal0;
    private boolean hasPending;

    public HardCircTransceiverBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.TRANSCEIVER_BLOCK_ENTITY, pos, state);
    }

    @Override
    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        serverTick(level, worldPosition, getBlockState());
    }

    private void serverTick(Level level, BlockPos pos, BlockState state) {
        if (!registered && !registrationFailed) {
            registerTransceiver(pos);
        }
        if (!registered) {
            return;
        }
        if (!tickLogged) {
            tickLogged = true;
            HardCircPack.LOGGER.info("收发器 #{} 服务端 tick 已启动：{}", transceiverId, pos.toShortString());
        }
        if (!hasPending) {
            return;
        }

        hasPending = false;
        int targetPower = pendingTerminal0;
        if (state.getValue(HardCircTransceiverBlock.T0_POWER) == targetPower) {
            return;
        }
        Block block = state.getBlock();
        level.setBlock(pos, state.setValue(HardCircTransceiverBlock.T0_POWER, targetPower), Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, block);
    }

    /**
     * 首个服务端 tick 完成 ID 分配与注册：构造函数在客户端也会执行且 level 可能为空，不能在其中注册。
     */
    private void registerTransceiver(BlockPos pos) {
        if (transceiverId < MIN_ID || transceiverId > MAX_ID) {
            transceiverId = allocateId();
        }
        if (transceiverId < MIN_ID) {
            markRegistrationFailed(pos);
            return;
        }

        Transceiver existing = TransceiverRegistry.get(transceiverId);
        if (existing != null && existing != this) {
            int reassigned = allocateId();
            HardCircPack.LOGGER.warn("收发器ID {} 已被其他方块占用，{} 处改分配为 {}",
                    transceiverId, pos.toShortString(), reassigned);
            transceiverId = reassigned;
            if (transceiverId < MIN_ID) {
                markRegistrationFailed(pos);
                return;
            }
        }

        if (!TransceiverRegistry.register(this)) {
            registrationFailed = true;
            return;
        }
        registered = true;
        // ID 可能由本次分配产生，立即标记为待保存，避免掉电/重进世界后重新分配。
        setChanged();
        HardCircPack.LOGGER.info("收发器 #{} 已注册：{}", transceiverId, pos.toShortString());
    }

    private void markRegistrationFailed(BlockPos pos) {
        registrationFailed = true;
        HardCircPack.LOGGER.error("收发器ID {}–{} 已耗尽，{} 处的收发器无法注册",
                MIN_ID, MAX_ID, pos.toShortString());
    }

    /**
     * @return 最小空闲 ID；1–255 全部占用时返回 0
     */
    private static int allocateId() {
        for (int id = MIN_ID; id <= MAX_ID; id++) {
            if (TransceiverRegistry.get(id) == null) {
                return id;
            }
        }
        return 0;
    }

    @Override
    public int getTransceiverId() {
        return transceiverId;
    }

    @Override
    public void onDataReceived(int[] terminalValues) {
        if (terminalValues == null || terminalValues.length == 0) {
            HardCircPack.LOGGER.warn("收发器 #{} 收到空端子数据，已忽略", transceiverId);
            return;
        }
        pendingTerminal0 = Math.max(MIN_POWER, Math.min(MAX_POWER, terminalValues[0]));
        hasPending = true;
    }

    @Override
    public void destroy() {
        if (!registered) {
            return;
        }
        registered = false;
        // 服务端停机时全局注册表已先清空，此处二次确认避免误报"未注册"。
        if (TransceiverRegistry.get(transceiverId) == this) {
            TransceiverRegistry.unregister(transceiverId);
        }
        HardCircPack.LOGGER.info("收发器 #{} 已销毁并注销", transceiverId);
    }

    @Override
    public void setRemoved() {
        // 1.21.3 Mojang 映射没有 onChunkUnloaded 钩子；收发器不持有硬件会话，
        // 区块卸载与物理移除都只需从注册表注销，重载后由新实例按 NBT 中的 ID 重新注册。
        destroy();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(NBT_TRANSCEIVER_ID, transceiverId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        transceiverId = tag.getInt(NBT_TRANSCEIVER_ID);
    }
}

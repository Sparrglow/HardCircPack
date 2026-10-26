package com.sparrglow.hardcircpack.transceiver;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * 收发器方块实体。端子配置（启用 / 方向 / 模式）与端子值全部存放在这里，由 NBT 持久化；
 * BlockState 只有一个 ACTIVE 布尔，配置改动不触发方块状态重建。
 * <p>
 * 阶段1只做数据搬运：onDataReceived 存待应用端子值，tick 中与当前值对比后按需通知邻居重查红石。
 * 不持有传输实例、不调用 pollReceived()，所有数据由 ServerTransportManager 路由投递。
 */
public class HardCircTransceiverBlockEntity extends BlockEntity implements Transceiver {

    private static final String NBT_TRANSCEIVER_ID = "transceiver_id";
    private static final String NBT_TERMINAL_ENABLED = "t_enabled";
    private static final String NBT_TERMINAL_OUTPUT = "t_output";
    private static final String NBT_TERMINAL_MODE = "t_mode";

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

    /** 各端子是否启用，默认全部启用。 */
    private final boolean[] terminalEnabled = new boolean[HardCircTransceiverBlock.TERMINAL_COUNT];
    /** 各端子方向：true=输出，false=输入，默认输出。 */
    private final boolean[] terminalOutput = new boolean[HardCircTransceiverBlock.TERMINAL_COUNT];
    /** 各端子模式：0=红石，1=数据，2=信号，默认红石。 */
    private final int[] terminalMode = new int[HardCircTransceiverBlock.TERMINAL_COUNT];

    /** 各端子当前值，0–15。由串口数据写入，不放进 BlockState。 */
    private final int[] terminalPower = new int[HardCircTransceiverBlock.TERMINAL_COUNT];
    private final int[] pendingTerminals = new int[HardCircTransceiverBlock.TERMINAL_COUNT];
    private boolean hasPending;

    public HardCircTransceiverBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.TRANSCEIVER_BLOCK_ENTITY, pos, state);
        Arrays.fill(terminalEnabled, true);
        Arrays.fill(terminalOutput, true);
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
        boolean changed = false;
        for (int i = 0; i < terminalPower.length; i++) {
            if (terminalPower[i] != pendingTerminals[i]) {
                terminalPower[i] = pendingTerminals[i];
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        // 端子值不在 BlockState 中，直接通知邻居重新向本方块查询红石信号
        level.updateNeighborsAt(pos, state.getBlock());
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
        // 新放置的方块只会收到方块状态更新，这里主动把配置数据推给客户端渲染器
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
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

    // ---------------------------------------------------------------- 端子配置

    public boolean isTerminalEnabled(int terminal) {
        return terminalEnabled[terminal];
    }

    public boolean isTerminalOutput(int terminal) {
        return terminalOutput[terminal];
    }

    public int getTerminalMode(int terminal) {
        return terminalMode[terminal];
    }

    /** 仅服务端调用：翻转端子启用位。 */
    public void toggleTerminalEnabled(int terminal) {
        terminalEnabled[terminal] = !terminalEnabled[terminal];
    }

    /** 仅服务端调用：翻转端子方向（输出↔输入）。 */
    public void toggleTerminalOutput(int terminal) {
        terminalOutput[terminal] = !terminalOutput[terminal];
    }

    /** 仅服务端调用：循环切换端子模式（红石→数据→信号→红石）。 */
    public void cycleTerminalMode(int terminal) {
        terminalMode[terminal] = (terminalMode[terminal] + 1) % HardCircTransceiverBlock.MODE_COUNT;
    }

    /**
     * 仅服务端调用：直接设定端子电平（调试命令用）。
     * 同时写入待应用值，避免下一次收到串口帧前的 tick 用旧数据覆盖。
     */
    public void setTerminalPower(int terminal, int power) {
        int clamped = clampPower(power);
        terminalPower[terminal] = clamped;
        pendingTerminals[terminal] = clamped;
    }

    /** 仅服务端调用：四个端子恢复默认状态（启用、输出、红石模式、电平 0）。 */
    public void resetTerminals() {
        for (int i = 0; i < HardCircTransceiverBlock.TERMINAL_COUNT; i++) {
            terminalEnabled[i] = true;
            terminalOutput[i] = true;
            terminalMode[i] = HardCircTransceiverBlock.MODE_REDSTONE;
            terminalPower[i] = 0;
            pendingTerminals[i] = 0;
        }
    }

    /**
     * 仅服务端调用：把四个端子的启用状态同步到 BlockState 的 t0_active–t3_active。
     *
     * @return 是否真的改变了方块状态（未变时调用方需自行推送方块实体数据）
     */
    public boolean syncActive() {
        if (level == null || level.isClientSide) {
            return false;
        }
        BlockState state = getBlockState();
        boolean changed = false;
        for (int i = 0; i < HardCircTransceiverBlock.TERMINAL_COUNT; i++) {
            if (state.getValue(HardCircTransceiverBlock.TERMINAL_ACTIVE[i]) != terminalEnabled[i]) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            return false;
        }
        BlockState newState = state;
        for (int i = 0; i < HardCircTransceiverBlock.TERMINAL_COUNT; i++) {
            newState = newState.setValue(HardCircTransceiverBlock.TERMINAL_ACTIVE[i], terminalEnabled[i]);
        }
        level.setBlock(worldPosition, newState, Block.UPDATE_ALL);
        return true;
    }

    /** 由方块的红石查询读取；索引已由调用方按端子面校验。 */
    public int getTerminalPower(int terminal) {
        return terminalPower[terminal];
    }

    @Override
    public void onDataReceived(int[] terminalValues) {
        if (terminalValues == null || terminalValues.length < HardCircTransceiverBlock.TERMINAL_COUNT) {
            HardCircPack.LOGGER.warn("收发器 #{} 收到端子数据不足 {} 个，已忽略",
                    transceiverId, HardCircTransceiverBlock.TERMINAL_COUNT);
            return;
        }
        pendingTerminals[0] = clampPower(terminalValues[0]);
        pendingTerminals[1] = clampPower(terminalValues[1]);
        pendingTerminals[2] = clampPower(terminalValues[2]);
        pendingTerminals[3] = clampPower(terminalValues[3]);
        hasPending = true;
    }

    private static int clampPower(int value) {
        return Math.max(MIN_POWER, Math.min(MAX_POWER, value));
    }

    // ---------------------------------------------------------------- 数据同步与持久化

    /** 区块加载与 {@code sendBlockUpdated} 时使用的完整数据，客户端渲染器据此绘制指示灯。 */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(NBT_TRANSCEIVER_ID, transceiverId);
        tag.putByteArray(NBT_TERMINAL_ENABLED, toBytes(terminalEnabled));
        tag.putByteArray(NBT_TERMINAL_OUTPUT, toBytes(terminalOutput));
        tag.putIntArray(NBT_TERMINAL_MODE, terminalMode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        transceiverId = tag.getInt(NBT_TRANSCEIVER_ID);
        loadBooleans(tag, NBT_TERMINAL_ENABLED, terminalEnabled);
        loadBooleans(tag, NBT_TERMINAL_OUTPUT, terminalOutput);
        loadModes(tag, NBT_TERMINAL_MODE, terminalMode);
    }

    private static void loadBooleans(CompoundTag tag, String key, boolean[] target) {
        if (!tag.contains(key)) {
            return;
        }
        byte[] values = tag.getByteArray(key);
        if (values.length != target.length) {
            HardCircPack.LOGGER.warn("NBT {} 长度 {} 与端子数 {} 不符，保留默认值",
                    key, values.length, target.length);
            return;
        }
        for (int i = 0; i < values.length; i++) {
            target[i] = values[i] != 0;
        }
    }

    private static byte[] toBytes(boolean[] values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) (values[i] ? 1 : 0);
        }
        return bytes;
    }

    private static void loadModes(CompoundTag tag, String key, int[] target) {
        if (!tag.contains(key)) {
            return;
        }
        int[] values = tag.getIntArray(key);
        if (values.length != target.length) {
            HardCircPack.LOGGER.warn("NBT {} 长度 {} 与端子数 {} 不符，保留默认值",
                    key, values.length, target.length);
            return;
        }
        for (int i = 0; i < values.length; i++) {
            target[i] = Math.max(HardCircTransceiverBlock.MODE_REDSTONE,
                    Math.min(HardCircTransceiverBlock.MODE_SIGNAL, values[i]));
        }
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
}

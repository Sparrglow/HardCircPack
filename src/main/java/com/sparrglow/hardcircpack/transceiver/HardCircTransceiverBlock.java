package com.sparrglow.hardcircpack.transceiver;

import com.mojang.serialization.MapCodec;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 收发器方块。四个水平面各承载一个端子，四个端子完全独立。
 * <p>
 * 端子数值与配置（输入/输出、红石/数据/信号）全部存放在 {@link HardCircTransceiverBlockEntity}，
 * 由 NBT 持久化；BlockState 只有四个端子的激活布尔（t0_active–t3_active，共 16 种状态）。
 * 逐端子的指示灯由客户端的 BlockEntityRenderer 读取方块实体数据绘制。
 */
public class HardCircTransceiverBlock extends Block implements EntityBlock {

    public static final MapCodec<HardCircTransceiverBlock> CODEC = simpleCodec(HardCircTransceiverBlock::new);

    /** 端子面映射（固定，不随方块朝向变化）：端子0=NORTH、1=EAST、2=SOUTH、3=WEST。 */
    public static final Direction[] TERMINAL_FACES = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public static final int TERMINAL_COUNT = TERMINAL_FACES.length;

    public static final int MODE_REDSTONE = 0;
    public static final int MODE_DATA = 1;
    public static final int MODE_SIGNAL = 2;
    public static final int MODE_COUNT = 3;

    /**
     * 四个端子的激活位，是 BlockState 中唯一的属性组（2^4 = 16 种状态）。
     * <p>
     * 只用于方块光照与按端子切换外观；端子数值与输入输出/红石-数据模式全部存放在方块实体中，
     * 不进入 BlockState，避免状态数爆炸。
     */
    public static final BooleanProperty[] TERMINAL_ACTIVE = new BooleanProperty[TERMINAL_COUNT];

    static {
        for (int i = 0; i < TERMINAL_COUNT; i++) {
            TERMINAL_ACTIVE[i] = BooleanProperty.create("t" + i + "_active");
        }
    }

    /** 同一玩家两次端子操作的冷却 tick 数，用于吸收连点抖动。 */
    private static final int INTERACT_COOLDOWN_TICKS = 6;

    /** 仅服务端主线程访问；键为玩家实例，玩家退出后随弱引用回收。 */
    private static final Map<Player, Long> LAST_ATTACK_TICK = new WeakHashMap<>();
    private static final Map<Player, Long> LAST_USE_TICK = new WeakHashMap<>();

    public HardCircTransceiverBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (BooleanProperty active : TERMINAL_ACTIVE) {
            state = state.setValue(active, true);
        }
        registerDefaultState(state);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (BooleanProperty active : TERMINAL_ACTIVE) {
            builder.add(active);
        }
    }

    /** 任一端子激活位为真时返回 true，供方块光照使用。 */
    public static boolean isAnyActive(BlockState state) {
        for (BooleanProperty active : TERMINAL_ACTIVE) {
            if (state.getValue(active)) {
                return true;
            }
        }
        return false;
    }

    /** 命中面所属的端子下标；UP/DOWN 与 null 不属于任何端子，返回 -1。 */
    public static int terminalIndex(Direction face) {
        for (int i = 0; i < TERMINAL_COUNT; i++) {
            if (TERMINAL_FACES[i] == face) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Mojang 映射下弱红石信号的方法名为 getSignal（Yarn 的 getWeakRedstonePower）。
     * <p>
     * 每个端子独立求值：只有"启用+输出+红石"三条件同时成立时才供电，且只向该端子所在面供电。
     * 配置与端子值都直接读方块实体；方块实体缺失（区块未加载）时按无信号处理。
     * 注意 direction 是"从查询方块指向本方块"的方向，因此判定被查询的面需要取 direction.getOpposite()。
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (direction == null) {
            return 0;
        }
        int terminal = terminalIndex(direction.getOpposite());
        if (terminal < 0) {
            return 0;
        }
        if (!(level.getBlockEntity(pos) instanceof HardCircTransceiverBlockEntity transceiver)) {
            return 0;
        }
        if (!transceiver.isTerminalEnabled(terminal)
                || !transceiver.isTerminalOutput(terminal)
                || transceiver.getTerminalMode(terminal) != MODE_REDSTONE) {
            return 0;
        }
        return transceiver.getTerminalPower(terminal);
    }

    /**
     * 站立左键切换命中面端子的启用位；下潜左键保留原版挖掘行为。
     * 由 HardCircPack 注册的 AttackBlockCallback 调用，客户端与服务端都会触发。
     */
    public static InteractionResult onAttack(Player player, Level level, BlockPos pos, Direction face) {
        int terminal = terminalIndex(face);
        if (terminal < 0 || player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            // 冷却期内直接拦下：仍取消挖掘，但不重复翻转配置
            if (isThrottled(LAST_ATTACK_TICK, player, level)) {
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof HardCircTransceiverBlockEntity transceiver) {
                transceiver.toggleTerminalEnabled(terminal);
                applyConfigChange(level, pos, transceiver);
                // 站立左键切启用（规范 2.2）：只提示当前操作玩家，Action Bar + 点击音效
                player.displayClientMessage(
                        Component.translatable("message.hardcirc_pack.toggle_enabled",
                                terminalStateName(transceiver.isTerminalEnabled(terminal))),
                        true);
                level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }
        // 返回 SUCCESS 以取消该次左键挖掘
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return interactTerminal(level, pos, player, hitResult);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hitResult) {
        return interactTerminal(level, pos, player, hitResult);
    }

    /**
     * 站立右键切换命中面端子的方向（输出↔输入），下潜右键循环模式（红石→数据→信号）。
     * 顶/底面不属于端子，交还原版处理。
     */
    private static InteractionResult interactTerminal(Level level, BlockPos pos,
                                                      Player player, BlockHitResult hitResult) {
        int terminal = terminalIndex(hitResult.getDirection());
        if (terminal < 0) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            if (isThrottled(LAST_USE_TICK, player, level)) {
                return InteractionResult.SUCCESS;
            }
            if (level.getBlockEntity(pos) instanceof HardCircTransceiverBlockEntity transceiver) {
                if (player.isShiftKeyDown()) {
                    transceiver.cycleTerminalMode(terminal);
                    // 只提示当前操作的玩家（Action Bar，非聊天框、非全服广播）。
                    // Mojang 映射下原版「sendMessage(text, true)」对应 displayClientMessage(Component, boolean)。
                    player.displayClientMessage(
                            Component.translatable("message.hardcirc_pack.mode_switched",
                                    modeDisplayName(transceiver.getTerminalMode(terminal))),
                            true);
                    level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
                } else {
                    transceiver.toggleTerminalOutput(terminal);
                    // 站立右键切方向（规范 2.2）：只提示当前操作玩家，Action Bar + 点击音效
                    player.displayClientMessage(
                            Component.translatable("message.hardcirc_pack.toggle_direction",
                                    terminalDirectionName(transceiver.isTerminalOutput(terminal))),
                            true);
                    level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                applyConfigChange(level, pos, transceiver);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** 端子模式对应的显示名（如「红石模式」），供 Action Bar 提示使用。 */
    private static Component modeDisplayName(int mode) {
        String key = switch (mode) {
            case MODE_DATA -> "message.hardcirc_pack.mode.data";
            case MODE_SIGNAL -> "message.hardcirc_pack.mode.signal";
            default -> "message.hardcirc_pack.mode.redstone";
        };
        return Component.translatable(key);
    }

    /** 端子启用位的显示名（启用 / 已禁用），供 Action Bar 提示使用。 */
    private static Component terminalStateName(boolean enabled) {
        return Component.translatable(enabled
                ? "command.hardcirc_pack.transceiver.state.enabled"
                : "command.hardcirc_pack.transceiver.state.disabled");
    }

    /** 端子方向的显示名（输出 / 输入），供 Action Bar 提示使用。 */
    private static Component terminalDirectionName(boolean output) {
        return Component.translatable(output
                ? "command.hardcirc_pack.transceiver.direction.output"
                : "command.hardcirc_pack.transceiver.direction.input");
    }

    /** 仅服务端主线程调用：该玩家距上次操作不足冷却时间时返回 true，否则记录本次时间。 */
    private static boolean isThrottled(Map<Player, Long> lastTicks, Player player, Level level) {
        long now = level.getGameTime();
        Long last = lastTicks.get(player);
        if (last != null && now - last < INTERACT_COOLDOWN_TICKS) {
            return true;
        }
        lastTicks.put(player, now);
        return false;
    }

    /**
     * 配置改动后的统一收尾：标记数据脏、按需刷新 ACTIVE、把方块实体数据推给客户端渲染器，
     * 再通知邻居重新查询红石信号，并播放原版拨杆声作为交互反馈。
     * <p>
     * 交互与调试命令共用，调用方必须已修改完方块实体数据且在服务端执行。
     */
    public static void applyConfigChange(Level level, BlockPos pos, HardCircTransceiverBlockEntity transceiver) {
        transceiver.setChanged();
        boolean activeChanged = transceiver.syncActive();
        BlockState state = level.getBlockState(pos);
        if (!activeChanged) {
            // ACTIVE 未变时 setBlock 不会触发方块实体数据同步，这里显式推送配置给客户端渲染器
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
        }
        level.updateNeighborsAt(pos, state.getBlock());
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, 0.6F);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HardCircTransceiverBlockEntity(pos, state);
    }

    /**
     * tick 只在服务端驱动；显式校验类型参数与 BlockEntityType 完全匹配（1.21.3 无 createTickerHelper）。
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != ModContent.TRANSCEIVER_BLOCK_ENTITY) {
            return null;
        }
        return (BlockEntityTicker<T>) (tickLevel, tickPos, tickState, blockEntity) ->
                ((HardCircTransceiverBlockEntity) blockEntity).tick();
    }
}

package com.sparrglow.hardcircpack.transceiver;

import com.mojang.serialization.MapCodec;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 收发器方块。阶段1只实现端子0的"启用+输出+红石"组合：
 * 端子值以 BlockState 为唯一真值，对外作为红石信号源供电。
 */
public class HardCircTransceiverBlock extends Block implements EntityBlock {

    public static final MapCodec<HardCircTransceiverBlock> CODEC = simpleCodec(HardCircTransceiverBlock::new);

    /** 端子0红石强度，0–15。 */
    public static final IntegerProperty T0_POWER = IntegerProperty.create("t0_power", 0, 15);
    /** 端子0启用位。 */
    public static final BooleanProperty T0_ENABLED = BooleanProperty.create("t0_enabled");
    /** 端子0方向：true=输出，false=输入。 */
    public static final BooleanProperty T0_OUTPUT = BooleanProperty.create("t0_output");
    /** 端子0模式：0=红石，1=数据，2=信号。 */
    public static final IntegerProperty T0_MODE = IntegerProperty.create("t0_mode", 0, 2);

    public static final int MODE_REDSTONE = 0;

    /** 端子0所在的面（阶段1固定为北面）；端子1/2/3 后续按 NORTH→EAST→SOUTH→WEST 顺时针排布。 */
    public static final Direction T0_FACE = Direction.NORTH;

    public HardCircTransceiverBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(T0_POWER, 0)
                .setValue(T0_ENABLED, true)
                .setValue(T0_OUTPUT, true)
                .setValue(T0_MODE, MODE_REDSTONE));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(T0_POWER, T0_ENABLED, T0_OUTPUT, T0_MODE);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Mojang 映射下弱红石信号的方法名为 getSignal（Yarn 的 getWeakRedstonePower）。
     * <p>
     * 只有"启用+输出+红石"三条件同时成立时才供电；且仅端子0所在的 NORTH 面输出，
     * 其余三个水平面与顶/底面恒为 0。注意 direction 是"从查询方块指向本方块"的方向，
     * 因此判定被查询的面需要取 direction.getOpposite()。
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (!state.getValue(T0_ENABLED)
                || !state.getValue(T0_OUTPUT)
                || state.getValue(T0_MODE) != MODE_REDSTONE) {
            return 0;
        }
        if (direction == null || direction.getOpposite() != T0_FACE) {
            return 0;
        }
        return state.getValue(T0_POWER);
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

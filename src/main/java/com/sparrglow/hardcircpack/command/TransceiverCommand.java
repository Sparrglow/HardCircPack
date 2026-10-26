package com.sparrglow.hardcircpack.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlock;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 收发器调试命令。
 * <p>
 * 端子配置与电平存放在方块实体中，{@code /setblock} 只能写 blockstate 的 t0_active–t3_active，
 * 无法驱动功能，因此这里提供服务端命令，以玩家准星命中方块定位目标，直接改方块实体数据。
 * <p>
 * 仅限 OP（权限等级 2），命令文本全部走翻译键。
 */
public final class TransceiverCommand {

    /** 准星探测距离（格）。 */
    private static final int REACH = 6;
    private static final int MAX_POWER = 15;

    private static final SimpleCommandExceptionType ERROR_NO_TARGET =
            new SimpleCommandExceptionType(Component.translatable("command.hardcirc_pack.transceiver.no_target"));
    private static final SimpleCommandExceptionType ERROR_NOT_TRANSCEIVER =
            new SimpleCommandExceptionType(Component.translatable("command.hardcirc_pack.transceiver.not_transceiver"));

    private TransceiverCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hardcirc")
                .then(Commands.literal("transceiver")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("info").executes(TransceiverCommand::info))
                        .then(Commands.literal("reset").executes(TransceiverCommand::reset))
                        .then(Commands.argument("terminal",
                                        IntegerArgumentType.integer(0, HardCircTransceiverBlock.TERMINAL_COUNT - 1))
                                .then(Commands.literal("enable").executes(TransceiverCommand::toggleEnabled))
                                .then(Commands.literal("direction").executes(TransceiverCommand::toggleDirection))
                                .then(Commands.literal("mode").executes(TransceiverCommand::cycleMode))
                                .then(Commands.literal("power")
                                        .then(Commands.argument("level", IntegerArgumentType.integer(0, MAX_POWER))
                                                .executes(TransceiverCommand::setPower))))));
    }

    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        target.source().sendSuccess(() -> Component.translatable(
                "command.hardcirc_pack.transceiver.info",
                target.blockEntity().getTransceiverId(),
                target.pos().toShortString()), false);
        for (int terminal = 0; terminal < HardCircTransceiverBlock.TERMINAL_COUNT; terminal++) {
            reportTerminal(target, terminal);
        }
        return HardCircTransceiverBlock.TERMINAL_COUNT;
    }

    private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        target.blockEntity().resetTerminals();
        HardCircTransceiverBlock.applyConfigChange(target.source().getLevel(), target.pos(), target.blockEntity());
        target.source().sendSuccess(() -> Component.translatable(
                "command.hardcirc_pack.transceiver.reset",
                target.blockEntity().getTransceiverId()), false);
        return HardCircTransceiverBlock.TERMINAL_COUNT;
    }

    private static int toggleEnabled(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        int terminal = IntegerArgumentType.getInteger(context, "terminal");
        target.blockEntity().toggleTerminalEnabled(terminal);
        applyAndReport(target, terminal);
        return 1;
    }

    private static int toggleDirection(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        int terminal = IntegerArgumentType.getInteger(context, "terminal");
        target.blockEntity().toggleTerminalOutput(terminal);
        applyAndReport(target, terminal);
        return 1;
    }

    private static int cycleMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        int terminal = IntegerArgumentType.getInteger(context, "terminal");
        target.blockEntity().cycleTerminalMode(terminal);
        applyAndReport(target, terminal);
        return 1;
    }

    private static int setPower(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Target target = resolve(context);
        int terminal = IntegerArgumentType.getInteger(context, "terminal");
        int power = IntegerArgumentType.getInteger(context, "level");
        target.blockEntity().setTerminalPower(terminal, power);
        applyAndReport(target, terminal);
        return power;
    }

    private static void applyAndReport(Target target, int terminal) {
        HardCircTransceiverBlock.applyConfigChange(target.source().getLevel(), target.pos(), target.blockEntity());
        reportTerminal(target, terminal);
    }

    /** 输出单个端子的当前状态，改完即回显，便于连续调试时确认。 */
    private static void reportTerminal(Target target, int terminal) {
        HardCircTransceiverBlockEntity blockEntity = target.blockEntity();
        Component enabled = Component.translatable(blockEntity.isTerminalEnabled(terminal)
                ? "command.hardcirc_pack.transceiver.state.enabled"
                : "command.hardcirc_pack.transceiver.state.disabled");
        Component direction = Component.translatable(blockEntity.isTerminalOutput(terminal)
                ? "command.hardcirc_pack.transceiver.direction.output"
                : "command.hardcirc_pack.transceiver.direction.input");
        Component mode = Component.translatable(modeKey(blockEntity.getTerminalMode(terminal)));
        target.source().sendSuccess(() -> Component.translatable(
                "command.hardcirc_pack.transceiver.info.line",
                terminal, enabled, direction, mode, blockEntity.getTerminalPower(terminal)), false);
    }

    private static String modeKey(int mode) {
        return switch (mode) {
            case HardCircTransceiverBlock.MODE_DATA -> "command.hardcirc_pack.transceiver.mode.data";
            case HardCircTransceiverBlock.MODE_SIGNAL -> "command.hardcirc_pack.transceiver.mode.signal";
            default -> "command.hardcirc_pack.transceiver.mode.redstone";
        };
    }

    /** 以玩家准星命中的方块实体作为目标。 */
    private static Target resolve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!(player.pick(REACH, 0.0F, false) instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            throw ERROR_NO_TARGET.create();
        }
        BlockPos pos = hit.getBlockPos();
        if (!(player.level().getBlockEntity(pos) instanceof HardCircTransceiverBlockEntity blockEntity)) {
            throw ERROR_NOT_TRANSCEIVER.create();
        }
        return new Target(source, pos, blockEntity);
    }

    private record Target(CommandSourceStack source, BlockPos pos, HardCircTransceiverBlockEntity blockEntity) {
    }
}

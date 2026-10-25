package com.sparrglow.hardcircpack.block;

import com.sparrglow.hardcircpack.menu.ControlHubOpenData;
import com.sparrglow.hardcircpack.menu.ControlHubScreenHandler;
import com.sparrglow.hardcircpack.registry.ModContent;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 总控台方块实体。不持有传输实例、不参与串口生命周期，仅作为菜单入口。
 */
public class ControlHubBlockEntity extends BlockEntity
        implements ExtendedScreenHandlerFactory<ControlHubOpenData> {

    public ControlHubBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.CONTROL_HUB_BLOCK_ENTITY, pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.hardcirc_pack.control_hub");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
        return new ControlHubScreenHandler(syncId, inventory, new ControlHubOpenData(this.worldPosition));
    }

    @Override
    public ControlHubOpenData getScreenOpeningData(ServerPlayer player) {
        return new ControlHubOpenData(this.worldPosition);
    }
}

package com.sparrglow.hardcircpack.menu;

import com.sparrglow.hardcircpack.block.ControlHubBlockEntity;
import com.sparrglow.hardcircpack.network.ControlHubPayloads;
import com.sparrglow.hardcircpack.registry.ModContent;
import com.sparrglow.hardcircpack.transport.HubState;
import com.sparrglow.hardcircpack.transport.HubStateListener;
import com.sparrglow.hardcircpack.transport.ServerTransportManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 总控台服务端菜单。本身没有物品槽，只承担「客户端操作 → 服务端权威执行」的转发与状态推送。
 * <p>
 * common 层类，严禁引用任何 owo-ui 类型。
 */
public class ControlHubScreenHandler extends AbstractContainerMenu implements HubStateListener {

    private final BlockPos pos;
    private final ServerPlayer serverPlayer;
    private final ControlHubBlockEntity blockEntity;

    public ControlHubScreenHandler(int syncId, Inventory inventory, ControlHubOpenData data) {
        super(ModContent.CONTROL_HUB_MENU, syncId);
        this.pos = data.pos();

        Player player = inventory.player;
        this.serverPlayer = player instanceof ServerPlayer current ? current : null;
        BlockEntity found = player.level().getBlockEntity(this.pos);
        this.blockEntity = found instanceof ControlHubBlockEntity hub ? hub : null;

        if (this.serverPlayer != null) {
            ServerTransportManager.instance().addListener(this);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.blockEntity != null
                && this.blockEntity.getLevel() == player.level()
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (this.serverPlayer != null) {
            ServerTransportManager.instance().removeListener(this);
        }
    }

    /**
     * 由 ServerTransportManager 在服务端主线程调用，把最新快照推给客户端。
     */
    @Override
    public void pushState(HubState state) {
        if (this.serverPlayer == null || this.serverPlayer.hasDisconnected()) {
            return;
        }
        ServerPlayNetworking.send(this.serverPlayer, ControlHubPayloads.StatePayload.from(state));
    }
}

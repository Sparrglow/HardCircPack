package com.sparrglow.hardcircpack.registry;

import com.sparrglow.hardcircpack.HardCircPack;
import com.sparrglow.hardcircpack.block.ControlHubBlock;
import com.sparrglow.hardcircpack.block.ControlHubBlockEntity;
import com.sparrglow.hardcircpack.menu.ControlHubOpenData;
import com.sparrglow.hardcircpack.menu.ControlHubScreenHandler;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlock;
import com.sparrglow.hardcircpack.transceiver.HardCircTransceiverBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 方块 / 方块实体 / 菜单类型注册。
 */
public final class ModContent {

    /** 1.21.3 起方块与物品构造前必须声明自己的注册键，否则 Properties 无法解析掉落表与翻译键。 */
    private static final ResourceKey<Block> CONTROL_HUB_KEY =
            ResourceKey.create(Registries.BLOCK, HardCircPack.id("control_hub"));
    private static final ResourceKey<Item> CONTROL_HUB_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, HardCircPack.id("control_hub"));
    private static final ResourceKey<Block> TRANSCEIVER_KEY =
            ResourceKey.create(Registries.BLOCK, HardCircPack.id("transceiver"));
    private static final ResourceKey<Item> TRANSCEIVER_ITEM_KEY =
            ResourceKey.create(Registries.ITEM, HardCircPack.id("transceiver"));

    public static final ControlHubBlock CONTROL_HUB =
            new ControlHubBlock(BlockBehaviour.Properties.of()
                    .setId(CONTROL_HUB_KEY)
                    .strength(2.0F));

    public static final BlockItem CONTROL_HUB_ITEM =
            new BlockItem(CONTROL_HUB, new Item.Properties().setId(CONTROL_HUB_ITEM_KEY));

    public static final HardCircTransceiverBlock TRANSCEIVER =
            new HardCircTransceiverBlock(BlockBehaviour.Properties.of()
                    .setId(TRANSCEIVER_KEY)
                    .strength(2.0F));

    public static final BlockItem TRANSCEIVER_ITEM =
            new BlockItem(TRANSCEIVER, new Item.Properties().setId(TRANSCEIVER_ITEM_KEY));

    public static final BlockEntityType<HardCircTransceiverBlockEntity> TRANSCEIVER_BLOCK_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, HardCircPack.id("transceiver"),
                    FabricBlockEntityTypeBuilder.create(HardCircTransceiverBlockEntity::new, TRANSCEIVER).build());

    public static final BlockEntityType<ControlHubBlockEntity> CONTROL_HUB_BLOCK_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, HardCircPack.id("control_hub"),
                    FabricBlockEntityTypeBuilder.create(ControlHubBlockEntity::new, CONTROL_HUB).build());

    public static final ExtendedScreenHandlerType<ControlHubScreenHandler, ControlHubOpenData> CONTROL_HUB_MENU =
            Registry.register(BuiltInRegistries.MENU, HardCircPack.id("control_hub"),
                    new ExtendedScreenHandlerType<>(ControlHubScreenHandler::new,
                            ControlHubOpenData.STREAM_CODEC));

    private ModContent() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.BLOCK, CONTROL_HUB_KEY, CONTROL_HUB);
        Registry.register(BuiltInRegistries.ITEM, CONTROL_HUB_ITEM_KEY, CONTROL_HUB_ITEM);
        Registry.register(BuiltInRegistries.BLOCK, TRANSCEIVER_KEY, TRANSCEIVER);
        Registry.register(BuiltInRegistries.ITEM, TRANSCEIVER_ITEM_KEY, TRANSCEIVER_ITEM);
    }
}

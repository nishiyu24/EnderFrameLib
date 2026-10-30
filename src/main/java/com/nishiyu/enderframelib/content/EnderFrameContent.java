package com.nishiyu.enderframelib.content;

import com.nishiyu.enderframelib.EnderFrameLib;
import com.nishiyu.enderframelib.api.template.EnderScreenBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.function.Supplier;

public final class EnderFrameContent {

    public static final ResourceLocation SCREEN_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(EnderFrameLib.MODID, "screen_block");

    public static Block SCREEN_BLOCK;
    public static BlockEntityType<ScreenBlockEntity> SCREEN_BLOCK_ENTITY_RAW;
    public static Supplier<BlockEntityType<ScreenBlockEntity>> SCREEN_BLOCK_ENTITY = () -> SCREEN_BLOCK_ENTITY_RAW;
    public static Item SCREEN_BLOCK_ITEM;

    private EnderFrameContent() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterEvent.class, event -> {
            event.register(Registries.BLOCK, helper -> {
                SCREEN_BLOCK = new EnderScreenBlock(
                        BlockBehaviour.Properties.of().strength(1.5f).noOcclusion(),
                        SCREEN_BLOCK_ENTITY
                );
                helper.register(SCREEN_BLOCK_ID, SCREEN_BLOCK);
            });

            event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
                SCREEN_BLOCK_ENTITY_RAW = BlockEntityType.Builder.of(ScreenBlockEntity::new, SCREEN_BLOCK).build(null);
                helper.register(SCREEN_BLOCK_ID, SCREEN_BLOCK_ENTITY_RAW);
            });

            event.register(Registries.ITEM, helper -> {
                SCREEN_BLOCK_ITEM = new BlockItem(SCREEN_BLOCK, new Item.Properties());
                helper.register(SCREEN_BLOCK_ID, SCREEN_BLOCK_ITEM);
            });
        });

        modEventBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS || event.getTabKey() == CreativeModeTabs.OP_BLOCKS) {
                if (SCREEN_BLOCK_ITEM != null) {
                    event.accept(SCREEN_BLOCK_ITEM);
                }
            }
        });
    }
}

package org.arkcraft.video_synchronizer;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.SoundType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.VideoManagerBlock;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.slf4j.Logger;

@Mod(Main.MODID)
public final class Main {
    public static final String MODID = "video_synchronizer";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<ScreenBlock> SCREEN_BLOCK = BLOCKS.register("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
                    .strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL)
                    .noLootTable().noOcclusion()));
    public static final RegistryObject<Item> SCREEN_ITEM = ITEMS.register("screen",
            () -> new BlockItem(SCREEN_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<ScreenBlockEntity>> SCREEN_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("screen", () -> BlockEntityType.Builder
                    .of(ScreenBlockEntity::new, SCREEN_BLOCK.get()).build(null));
    public static final RegistryObject<VideoManagerBlock> VIDEO_MANAGER_BLOCK = BLOCKS.register("video_manager",
            () -> new VideoManagerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(-1.0F, 3_600_000.0F).sound(SoundType.METAL)
                    .noLootTable()));
    public static final RegistryObject<Item> VIDEO_MANAGER_ITEM = ITEMS.register("video_manager",
            () -> new BlockItem(VIDEO_MANAGER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<VideoManagerBlockEntity>> VIDEO_MANAGER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("video_manager", () -> BlockEntityType.Builder
                    .of(VideoManagerBlockEntity::new, VIDEO_MANAGER_BLOCK.get()).build(null));
    public static final RegistryObject<CreativeModeTab> VIDEO_SYNCHRONIZER_TAB =
            CREATIVE_MODE_TABS.register("video_synchronizer", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.video_synchronizer"))
                    .icon(() -> SCREEN_ITEM.get().getDefaultInstance())
                    .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                    .displayItems((parameters, output) -> {
                        output.accept(SCREEN_ITEM.get());
                        output.accept(VIDEO_MANAGER_ITEM.get());
                    })
                    .build());

    public Main(FMLJavaModLoadingContext context) {
        var modEventBus = context.getModEventBus();
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(VideoNetwork::register);
    }
}

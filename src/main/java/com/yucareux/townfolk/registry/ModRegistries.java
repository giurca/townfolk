package com.yucareux.townfolk.registry;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.block.CharterStoneBlock;
import com.yucareux.townfolk.block.TradePostBlock;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.blockentity.TradePostBlockEntity;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Central registry holders. Blocks, items, block entities, attachments — all
 * deferred-registered against NeoForge's registry system and wired to the mod
 * event bus by {@link Townfolk}.
 */
public final class ModRegistries {

   public static final DeferredRegister.Blocks BLOCKS =
      DeferredRegister.createBlocks(Townfolk.MODID);
   public static final DeferredRegister.Items ITEMS =
      DeferredRegister.createItems(Townfolk.MODID);
   public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Townfolk.MODID);
   public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
      DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Townfolk.MODID);
   public static final DeferredRegister<EntityType<?>> ENTITIES =
      DeferredRegister.create(Registries.ENTITY_TYPE, Townfolk.MODID);
   public static final DeferredRegister<MenuType<?>> MENUS =
      DeferredRegister.create(Registries.MENU, Townfolk.MODID);

   /** Storage-config popup menu. Constructor reads barrel pos from the
    *  extra-data buffer the server attaches when opening the menu. */
   public static final Supplier<MenuType<com.yucareux.townfolk.world.inventory.StorageConfigMenu>>
      STORAGE_CONFIG_MENU = MENUS.register(
         "storage_config",
         () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
            (id, inv, buf) -> new com.yucareux.townfolk.world.inventory.StorageConfigMenu(id, inv, buf)));

   /** Crop-plan editor menu. Constructor reads parcel id from the
    *  extra-data buffer. */
   public static final Supplier<MenuType<com.yucareux.townfolk.world.inventory.CropPlanMenu>>
      CROP_PLAN_MENU = MENUS.register(
         "crop_plan",
         () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
            (id, inv, buf) -> new com.yucareux.townfolk.world.inventory.CropPlanMenu(id, inv, buf)));

   public static final Supplier<EntityType<LlmTownsfolk>> LLM_TOWNSFOLK = ENTITIES.register(
      "llm_townsfolk",
      () -> EntityType.Builder.<LlmTownsfolk>of(LlmTownsfolk::new, MobCategory.MISC)
         .sized(0.6F, 1.95F)
         .clientTrackingRange(10)
         .build("llm_townsfolk")
   );

   public static final Supplier<AttachmentType<LlmVillagerComponent>> LLM_VILLAGER =
      ATTACHMENTS.register(
         "llm_villager",
         () -> AttachmentType.builder(() -> LlmVillagerComponent.EMPTY)
            .serialize(LlmVillagerComponent.CODEC)
            .build()
      );

   /** Charter Stone — town anchor block.
    *
    *  Registry id stays {@code town_square} for save-file compatibility
    *  with worlds that predate the rename. Class + display name + model
    *  have been updated to "Charter Stone." */
   public static final Supplier<CharterStoneBlock> TOWN_SQUARE_BLOCK = BLOCKS.register(
      "town_square",
      () -> new CharterStoneBlock(BlockBehaviour.Properties.of()
         .mapColor(MapColor.STONE)
         .strength(3.5F, 6.0F)
         .requiresCorrectToolForDrops()
         .sound(SoundType.STONE)
         // Monolith model is non-cube — neighbour faces would otherwise
         // get culled where they touch the bounding cube.
         .noOcclusion())
   );

   public static final Supplier<Item> TOWN_SQUARE_ITEM = ITEMS.register(
      "town_square",
      () -> new BlockItem(TOWN_SQUARE_BLOCK.get(), new Item.Properties())
   );

   /** Crafted tool that lets the player assign parcels of land to a villager.
    *  Right-click villager to start binding, then right-click two corners.
    *  No durability — single-stack item. */
   public static final Supplier<Item> SURVEYOR_STAKE = ITEMS.register(
      "surveyor_stake",
      () -> new com.yucareux.townfolk.item.SurveyorStakeItem(new Item.Properties().stacksTo(16))
   );

   /** Building Permit — single-use document the player right-clicks a
    *  building marker block (Charter Stone, bed, future workshop /
    *  library markers) with to override the default volume / height
    *  caps on that specific building. Consumed on Apply; no effect
    *  if the click target isn't a marker block. Stack size 16 so
    *  players can carry a few for batch construction. */
   public static final Supplier<Item> BUILDING_PERMIT = ITEMS.register(
      "building_permit",
      () -> new com.yucareux.townfolk.item.BuildingPermitItem(new Item.Properties().stacksTo(16))
   );

   public static final Supplier<BlockEntityType<TownSquareBlockEntity>> TOWN_SQUARE_BE =
      BLOCK_ENTITIES.register(
         "town_square",
         () -> BlockEntityType.Builder.of(TownSquareBlockEntity::new, TOWN_SQUARE_BLOCK.get()).build(null)
      );

   public static final Supplier<TradePostBlock> TRADE_POST_BLOCK = BLOCKS.register(
      "trade_post",
      () -> new TradePostBlock(BlockBehaviour.Properties.of()
         .mapColor(MapColor.WOOD)
         .strength(2.0F, 4.0F)
         .requiresCorrectToolForDrops()
         .sound(SoundType.WOOD)
         // The model is a thin post + sign + base, not a full 1×1×1
         // cube. Without noOcclusion the renderer treats this block as
         // opaque and culls every neighbouring block's faces that touch
         // it — leaves a black hole in the world around the post.
         .noOcclusion())
   );

   public static final Supplier<Item> TRADE_POST_ITEM = ITEMS.register(
      "trade_post",
      () -> new BlockItem(TRADE_POST_BLOCK.get(), new Item.Properties())
   );

   public static final Supplier<BlockEntityType<TradePostBlockEntity>> TRADE_POST_BE =
      BLOCK_ENTITIES.register(
         "trade_post",
         () -> BlockEntityType.Builder.of(TradePostBlockEntity::new, TRADE_POST_BLOCK.get()).build(null)
      );

   public static void register(IEventBus modEventBus) {
      BLOCKS.register(modEventBus);
      ITEMS.register(modEventBus);
      BLOCK_ENTITIES.register(modEventBus);
      ATTACHMENTS.register(modEventBus);
      ENTITIES.register(modEventBus);
      MENUS.register(modEventBus);
      modEventBus.addListener(ModRegistries::onBuildCreativeTabs);
   }

   /** Surface mod items in the appropriate vanilla creative tabs so players
    *  can find them via the standard menu (and so EMI/JEI tab-listings work
    *  correctly). Without this, items only show up in registry-wide search. */
   private static void onBuildCreativeTabs(
         net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
      // Surveyor's Stake + Building Permit → Tools & Utilities tab.
      if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES) {
         event.accept(SURVEYOR_STAKE.get());
         event.accept(BUILDING_PERMIT.get());
      }
      // Trade Post → Functional Blocks tab. Town Square is auto-
      // inserted into this same tab by NeoForge (BlockItem default
      // behaviour); accepting it again throws
      // "already exists in the tab's list" and crashes the event,
      // so we only explicitly add the items that DON'T get the
      // auto treatment.
      if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
         event.accept(TRADE_POST_ITEM.get());
      }
   }

   private ModRegistries() {
   }
}

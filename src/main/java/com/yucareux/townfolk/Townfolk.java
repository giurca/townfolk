package com.yucareux.townfolk;

import com.yucareux.townfolk.command.TownfolkCommands;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.registry.ModRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Townfolk.MODID)
public final class Townfolk {

   public static final String MODID = "townfolk";
   public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

   public Townfolk(IEventBus modEventBus, ModContainer container) {
      container.registerConfig(ModConfig.Type.COMMON, TownfolkConfig.SPEC);
      ModRegistries.register(modEventBus);
      modEventBus.addListener(this::onBuildCreativeTabs);
      modEventBus.addListener(this::onEntityAttributeCreation);
      NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, this::onRegisterCommands);
      LOGGER.info("Tellus Townfolk loaded.");
   }

   private void onEntityAttributeCreation(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
      // Reuse vanilla Villager attributes for our subclass.
      event.put(ModRegistries.LLM_TOWNSFOLK.get(), net.minecraft.world.entity.npc.Villager.createAttributes().build());
   }

   private void onRegisterCommands(RegisterCommandsEvent event) {
      TownfolkCommands.register(event.getDispatcher());
   }

   private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
      // Surface the Town Square in the Functional Blocks tab for now; future
      // versions can ship a dedicated Townfolk tab if we grow more items.
      if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
         event.accept(ModRegistries.TOWN_SQUARE_ITEM.get());
      }
   }
}

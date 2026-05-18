package com.yucareux.townfolk.client;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.registry.ModRegistries;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-side mod-bus setup. Registers the entity renderer for our
 * {@link com.yucareux.townfolk.entity.LlmTownsfolk} subclass — reuses the
 * vanilla {@link VillagerRenderer} since our entity is a {@code Villager}
 * subclass. Visual fidelity comes for free, and supplementary info
 * (current activity, todo count, memory size, anchor state) is surfaced
 * via the Jade tooltip plugin instead of a custom nameplate.
 */
@EventBusSubscriber(modid = Townfolk.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TownfolkClientSetup {

   @SubscribeEvent
   public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
      event.registerEntityRenderer(ModRegistries.LLM_TOWNSFOLK.get(), VillagerRenderer::new);
   }

   /** Bind our storage-config menu to its client screen so vanilla
    *  {@code player.openMenu(...)} on the server automatically opens
    *  the right UI on the client. */
   @SubscribeEvent
   public static void onRegisterScreens(RegisterMenuScreensEvent event) {
      net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<
         com.yucareux.townfolk.world.inventory.StorageConfigMenu,
         com.yucareux.townfolk.client.screen.StorageConfigScreen> storageCtor =
         (menu, inv, title) ->
            new com.yucareux.townfolk.client.screen.StorageConfigScreen(menu, inv,
               title == null ? Component.literal("Configure storage") : title);
      event.register(ModRegistries.STORAGE_CONFIG_MENU.get(), storageCtor);

      net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor<
         com.yucareux.townfolk.world.inventory.CropPlanMenu,
         com.yucareux.townfolk.client.screen.CropPlanScreen> cropCtor =
         (menu, inv, title) ->
            new com.yucareux.townfolk.client.screen.CropPlanScreen(menu, inv,
               title == null ? Component.literal("Plant plan") : title);
      event.register(ModRegistries.CROP_PLAN_MENU.get(), cropCtor);
   }

   private TownfolkClientSetup() {}
}

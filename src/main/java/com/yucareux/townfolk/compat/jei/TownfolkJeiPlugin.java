package com.yucareux.townfolk.compat.jei;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.client.screen.StorageConfigScreen;
import com.yucareux.townfolk.network.SetGhostSlotPayload;
import com.yucareux.townfolk.world.inventory.StorageConfigMenu;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * JEI integration. Lets the player drag items from JEI's sidebar
 * directly into the 16 ghost filter slots on a {@link StorageConfigScreen}
 * instead of having to pick the item up from inventory first.
 *
 * Class-loaded only when JEI is present (the {@link JeiPlugin}
 * annotation is JEI's discovery hook), so the mod is safe to run
 * without JEI installed.
 */
@JeiPlugin
public final class TownfolkJeiPlugin implements IModPlugin {

   public static final ResourceLocation UID =
      ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, "jei_compat");

   @Override
   public ResourceLocation getPluginUid() { return UID; }

   @Override
   public void registerGuiHandlers(IGuiHandlerRegistration r) {
      r.addGhostIngredientHandler(StorageConfigScreen.class, new FilterGhostHandler());
      r.addGhostIngredientHandler(
         com.yucareux.townfolk.client.screen.CropPlanScreen.class, new CropPlanGhostHandler());
   }

   /** Bridges JEI's typed ghost-drag protocol to our
    *  {@link SetGhostSlotPayload}. JEI calls {@code getTargetsTyped}
    *  while the player is dragging; each target's {@code accept} fires
    *  when the player drops on that slot's rect. */
   private static final class FilterGhostHandler
      implements IGhostIngredientHandler<StorageConfigScreen> {

      @Override
      public <I> List<Target<I>> getTargetsTyped(StorageConfigScreen screen,
                                                  ITypedIngredient<I> ingredient,
                                                  boolean doStart) {
         List<Target<I>> targets = new ArrayList<>();
         // Only ItemStack ingredients map onto our filter — fluid /
         // mod-attribute ingredients are ignored.
         Object raw = ingredient.getIngredient();
         if (!(raw instanceof ItemStack)) return targets;

         StorageConfigMenu menu = screen.getMenu();
         for (int i = 0; i < StorageConfigMenu.filterRegionSize(); i++) {
            final int slotIdx = i;
            var slot = menu.getSlot(i);
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            Rect2i rect = new Rect2i(x, y, 16, 16);
            targets.add(new Target<I>() {
               @Override public Rect2i getArea() { return rect; }
               @Override public void accept(I ing) {
                  if (ing instanceof ItemStack stack && !stack.isEmpty()) {
                     // Single-count copy is the only thing the filter
                     // cares about — count is meaningless for matching.
                     PacketDistributor.sendToServer(
                        new SetGhostSlotPayload(slotIdx, stack.copyWithCount(1)));
                  }
               }
            });
         }
         return targets;
      }

      @Override
      public void onComplete() {}     // nothing to clean up
   }

   /** Same shape as the storage filter handler, just for the crop-plan
    *  screen's plan-row slots. */
   private static final class CropPlanGhostHandler
      implements IGhostIngredientHandler<com.yucareux.townfolk.client.screen.CropPlanScreen> {

      @Override
      public <I> List<Target<I>> getTargetsTyped(
            com.yucareux.townfolk.client.screen.CropPlanScreen screen,
            ITypedIngredient<I> ingredient, boolean doStart) {
         List<Target<I>> targets = new ArrayList<>();
         if (!(ingredient.getIngredient() instanceof ItemStack)) return targets;
         var menu = screen.getMenu();
         for (int i = 0; i < com.yucareux.townfolk.world.inventory.CropPlanMenu.filterRegionSize(); i++) {
            final int slotIdx = i;
            var slot = menu.getSlot(i);
            int x = screen.getGuiLeft() + slot.x;
            int y = screen.getGuiTop() + slot.y;
            Rect2i rect = new Rect2i(x, y, 16, 16);
            targets.add(new Target<I>() {
               @Override public Rect2i getArea() { return rect; }
               @Override public void accept(I ing) {
                  if (ing instanceof ItemStack stack && !stack.isEmpty()) {
                     PacketDistributor.sendToServer(
                        new SetGhostSlotPayload(slotIdx, stack.copyWithCount(1)));
                  }
               }
            });
         }
         return targets;
      }

      @Override public void onComplete() {}
   }
}

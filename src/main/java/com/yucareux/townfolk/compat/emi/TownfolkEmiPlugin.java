package com.yucareux.townfolk.compat.emi;

import com.yucareux.townfolk.client.screen.StorageConfigScreen;
import com.yucareux.townfolk.network.SetGhostSlotPayload;
import com.yucareux.townfolk.world.inventory.StorageConfigMenu;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * EMI integration — same role as {@link com.yucareux.townfolk.compat.jei.TownfolkJeiPlugin}
 * but for the EMI recipe viewer. Lets the player drag items from EMI's
 * sidebar onto a {@link StorageConfigScreen} filter slot.
 *
 * Class-loaded only when EMI is present (discovered via the
 * {@link EmiEntrypoint} annotation), so safe to run without EMI
 * installed.
 */
@EmiEntrypoint
public final class TownfolkEmiPlugin implements EmiPlugin {

   @Override
   public void register(EmiRegistry registry) {
      // EMI's ghost-drag uses addDragDropHandler. The handler returns
      // true if it consumed the drop. We loop over our 16 filter
      // slots and pick whichever the cursor is over.
      registry.addDragDropHandler(StorageConfigScreen.class,
         (screen, emiStack, x, y) -> {
            StorageConfigMenu menu = screen.getMenu();
            for (int i = 0; i < StorageConfigMenu.filterRegionSize(); i++) {
               var slot = menu.getSlot(i);
               int sx = screen.getGuiLeft() + slot.x;
               int sy = screen.getGuiTop() + slot.y;
               if (x >= sx && x < sx + 16 && y >= sy && y < sy + 16) {
                  // EmiIngredient → first concrete EmiStack → ItemStack.
                  // Tag / multi-stack ingredients use the first stack
                  // (whatever EMI is rendering) as the representative.
                  var stacks = emiStack.getEmiStacks();
                  if (stacks.isEmpty()) return false;
                  ItemStack stack = stacks.get(0).getItemStack();
                  if (!stack.isEmpty()) {
                     PacketDistributor.sendToServer(
                        new SetGhostSlotPayload(i, stack.copyWithCount(1)));
                     return true;
                  }
                  return false;
               }
            }
            return false;
         });

      // Same handler shape for the crop-plan editor.
      registry.addDragDropHandler(
         com.yucareux.townfolk.client.screen.CropPlanScreen.class,
         (screen, emiStack, x, y) -> {
            var menu = screen.getMenu();
            for (int i = 0; i < com.yucareux.townfolk.world.inventory.CropPlanMenu.filterRegionSize(); i++) {
               var slot = menu.getSlot(i);
               int sx = screen.getGuiLeft() + slot.x;
               int sy = screen.getGuiTop() + slot.y;
               if (x >= sx && x < sx + 16 && y >= sy && y < sy + 16) {
                  var stacks = emiStack.getEmiStacks();
                  if (stacks.isEmpty()) return false;
                  ItemStack stack = stacks.get(0).getItemStack();
                  if (!stack.isEmpty()) {
                     PacketDistributor.sendToServer(
                        new SetGhostSlotPayload(i, stack.copyWithCount(1)));
                     return true;
                  }
                  return false;
               }
            }
            return false;
         });
   }
}

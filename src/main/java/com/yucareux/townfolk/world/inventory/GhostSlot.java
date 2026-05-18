package com.yucareux.townfolk.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A slot that displays an item without taking it from the player's
 * inventory. Used by {@link StorageConfigMenu} for the 16 filter slots
 * — when the player clicks the slot with an item on their cursor, a
 * single-count COPY is stored as the filter spec; the cursor stack is
 * untouched. Clicking with an empty cursor clears the slot.
 *
 * The actual click handling lives in {@link StorageConfigMenu#clicked}
 * (the menu intercepts before vanilla slot logic runs); this class only
 * advertises the slot's "ghost" intent through {@link #mayPlace} /
 * {@link #mayPickup} so vanilla code paths that ignore the click
 * override at least don't move items out of inventory.
 */
public final class GhostSlot extends Slot {

   public GhostSlot(Container container, int index, int x, int y) {
      super(container, index, x, y);
   }

   @Override
   public boolean mayPlace(ItemStack stack) { return true; }

   /** No taking — ghost slots aren't a destination for the cursor. */
   @Override
   public boolean mayPickup(Player player) { return false; }

   /** Counts are irrelevant for ghost-slot filter matching; cap at 1
    *  so the UI never shows a stack number on the slot. */
   @Override
   public int getMaxStackSize() { return 1; }

   @Override
   public int getMaxStackSize(ItemStack stack) { return 1; }
}

package com.yucareux.townfolk.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Cross-mod storage compatibility shim. Town routing code reads / writes
 * through the vanilla {@link Container} interface, but the modern
 * NeoForge inventory contract is {@link IItemHandler}: every well-behaved
 * storage mod (Sophisticated Storage, Create item vaults, Functional
 * Storage, Iron Chests, ...) exposes its inventory via that capability,
 * but many do not implement {@link Container} natively.
 *
 * <p>This class is the seam: pass it a {@link BlockPos}, get back a
 * {@link Container} view that delegates to whatever {@link IItemHandler}
 * the block exposes. Vanilla containers (chest / barrel / shulker box)
 * also work — they expose the same capability natively, so the same
 * code path handles them too.
 *
 * <p>Slot semantics:
 * <ul>
 *   <li>{@link Container#getItem(int)} → {@link IItemHandler#getStackInSlot(int)}
 *   <li>{@link Container#removeItem(int, int)} → {@link IItemHandler#extractItem(int, int, boolean)}
 *   <li>{@link Container#setItem(int, ItemStack)} → {@link IItemHandlerModifiable#setStackInSlot(int, ItemStack)}
 *       if available, else best-effort extract + insert
 * </ul>
 *
 * <p>The adapter's {@code stillValid(Player)} returns true unconditionally —
 * proximity is gated upstream by the routing layer, and we don't want
 * a wrapped Sophisticated Storage barrel to fail container validity
 * because it's expecting its own custom container check.
 */
public final class ContainerAdapters {

   private ContainerAdapters() {}

   /**
    * Capability-driven container view of the block at {@code pos}.
    * Returns {@code null} if nothing at that position exposes an
    * {@link IItemHandler} — i.e. it isn't storage.
    *
    * <p>Note: the returned adapter holds a strong reference to the
    * {@link IItemHandler} captured at lookup time. Callers shouldn't
    * cache it across ticks — re-resolve when needed so we don't end
    * up reading from a stale handler after a block break / replace.
    */
   public static Container at(ServerLevel level, BlockPos pos) {
      IItemHandler handler = level.getCapability(
         Capabilities.ItemHandler.BLOCK, pos, null);
      if (handler == null) return null;
      return new HandlerContainer(handler);
   }

   /** True iff the block at {@code pos} exposes an {@link IItemHandler}
    *  capability — i.e. counts as "storage" under the new contract. */
   public static boolean isStorageAt(ServerLevel level, BlockPos pos) {
      return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
   }

   /** Container view backed by an IItemHandler. Implements the subset
    *  of Container ops the rest of the codebase actually uses; less-
    *  commonly invoked ops (clearContent, isEmpty, count-anything)
    *  have working but unoptimised implementations. */
   private static final class HandlerContainer implements Container {

      private final IItemHandler handler;

      HandlerContainer(IItemHandler handler) {
         this.handler = handler;
      }

      @Override public int getContainerSize() { return handler.getSlots(); }

      @Override public boolean isEmpty() {
         for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
         }
         return true;
      }

      @Override public ItemStack getItem(int slot) {
         if (slot < 0 || slot >= handler.getSlots()) return ItemStack.EMPTY;
         return handler.getStackInSlot(slot);
      }

      @Override public ItemStack removeItem(int slot, int amount) {
         if (slot < 0 || slot >= handler.getSlots() || amount <= 0) return ItemStack.EMPTY;
         return handler.extractItem(slot, amount, false);
      }

      @Override public ItemStack removeItemNoUpdate(int slot) {
         if (slot < 0 || slot >= handler.getSlots()) return ItemStack.EMPTY;
         return handler.extractItem(slot, Integer.MAX_VALUE, false);
      }

      @Override public void setItem(int slot, ItemStack stack) {
         if (slot < 0 || slot >= handler.getSlots()) return;
         if (handler instanceof IItemHandlerModifiable mod) {
            mod.setStackInSlot(slot, stack);
         } else {
            // Read-only handler — best effort: drop the current
            // contents and try to insert the new ones. Some
            // unmodifiable handlers (e.g. furnace-output) WILL
            // refuse the insert; we report success either way
            // because the contract is fire-and-forget.
            handler.extractItem(slot, Integer.MAX_VALUE, false);
            if (!stack.isEmpty()) handler.insertItem(slot, stack, false);
         }
      }

      /** Convenience helper that loops insertItem across slots and
       *  returns the leftover. Not a Container override — Container
       *  has no addItem(ItemStack) signature in 1.21.1; this is a
       *  utility we kept in case a caller wants merge-semantics. */
      public ItemStack addItemMerging(ItemStack stack) {
         ItemStack remaining = stack.copy();
         for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, false);
         }
         return remaining;
      }

      @Override public int getMaxStackSize() { return 64; }

      @Override public void setChanged() {
         // The underlying handler is responsible for marking its
         // backing BlockEntity dirty on insert/extract. No-op here.
      }

      @Override public boolean stillValid(Player player) {
         // Proximity / auth gated upstream; trust the routing layer.
         return true;
      }

      @Override public void clearContent() {
         if (handler instanceof IItemHandlerModifiable mod) {
            for (int i = 0; i < handler.getSlots(); i++) {
               mod.setStackInSlot(i, ItemStack.EMPTY);
            }
         } else {
            for (int i = 0; i < handler.getSlots(); i++) {
               handler.extractItem(i, Integer.MAX_VALUE, false);
            }
         }
      }
   }
}

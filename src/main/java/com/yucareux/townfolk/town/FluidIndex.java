package com.yucareux.townfolk.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Fluid-handler discovery helper for Stage 23. Sister to
 * {@link StorageIndex} (which deals with item containers) but talks
 * to the NeoForge {@link Capabilities#FluidHandler} surface.
 *
 * <p>The fluid verbs ({@code Fill}/{@code Drain}/{@code Pour}) call
 * into this from {@code world/verbs/} — keeps the verb files clean
 * of capability boilerplate, and gives the bridge a single chokepoint
 * to swap implementations if NeoForge changes the capability shape
 * between minor versions.
 *
 * <p>No persistence — the index is purely a runtime lookup. Tanks
 * are registered the same way as item barrels (sneak-right-click
 * with empty hand → StorageRegistry), and the capability check
 * separates fluid-handlers from item-handlers at query time. A
 * single registered position can carry both (Create barrels do).
 */
public final class FluidIndex {

   private FluidIndex() {}

   /** Resolve the {@link IFluidHandler} capability at {@code pos}, or
    *  null if the block / block entity at that position doesn't
    *  expose one. Tries every face of the block — most fluid tanks
    *  publish on UP, but some Create blocks publish on the
    *  flow-direction face.
    *
    *  <p>{@code level} must be a ServerLevel. The capability lookup
    *  is fully NeoForge-vanilla — works against any mod's tanks
    *  (Create, Mekanism, etc.) without compile-time dependencies. */
   public static IFluidHandler at(ServerLevel level, BlockPos pos) {
      // Try the directionless (block-level) lookup first.
      IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
      if (h != null) return h;
      // Then the six faces — first hit wins.
      for (Direction d : Direction.values()) {
         IFluidHandler face = level.getCapability(
            Capabilities.FluidHandler.BLOCK, pos, d);
         if (face != null) return face;
      }
      return null;
   }

   /** Resolve the {@link IFluidHandlerItem} on an item stack — i.e.
    *  a bucket-like container the villager is holding. Returns null
    *  if the stack doesn't expose a fluid handler. */
   public static IFluidHandlerItem onStack(ItemStack stack) {
      if (stack == null || stack.isEmpty()) return null;
      return stack.getCapability(Capabilities.FluidHandler.ITEM);
   }

   /** Peek the dominant fluid in a tank — returns the FIRST non-empty
    *  internal "stack" the handler reports, or {@link FluidStack#EMPTY}
    *  if the tank is empty / unreachable. Used by the LLM-side fluid
    *  prompt to surface what's currently held. */
   public static FluidStack contents(IFluidHandler handler) {
      if (handler == null) return FluidStack.EMPTY;
      for (int i = 0; i < handler.getTanks(); i++) {
         FluidStack s = handler.getFluidInTank(i);
         if (!s.isEmpty()) return s;
      }
      return FluidStack.EMPTY;
   }

   /** Total volume (mB) currently held across every tank slot. */
   public static int totalAmount(IFluidHandler handler) {
      if (handler == null) return 0;
      int n = 0;
      for (int i = 0; i < handler.getTanks(); i++) {
         n += handler.getFluidInTank(i).getAmount();
      }
      return n;
   }
}

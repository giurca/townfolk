package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.RecipeCatalog;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/**
 * {@code [ACTION: plant [seed-hint]]} — find the closest tilled
 * farmland within 8 blocks and place a seed (from hint, or auto-
 * picked from the villager's first viable {@link CropBlock} seed).
 *
 * <p>Extracted from {@code ToolDispatcher.doPlant} in stage 17a.4.
 */
public final class PlantVerb {

   private PlantVerb() {}

   public static void run(VerbContext ctx) {
      String hint = ctx.body();
      var inv = ctx.actor().getInventory();
      Item seed = hint.isEmpty() ? null : RecipeCatalog.resolveItemOpt(hint).orElse(null);
      if (seed == null) {
         for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock) {
               seed = s.getItem(); break;
            }
         }
      }
      if (seed == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "plant — no seeds in your bag");
         return;
      }
      final Item seedF = seed;
      BlockPos target = BlockTaskHelpers.findClosest(ctx.level(), ctx.actor().blockPosition(), 8, state -> {
         if (!state.is(Blocks.FARMLAND)) return false;
         return true; // "air above" verified at execute time
      });
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "plant — no tilled farmland within 8 blocks");
         return;
      }
      BlockPos plantAt = target.above();
      BlockTaskHelpers.enqueueBlockTask(ctx.level(), ctx.actor(), target, "plant", (lvl, v, pos) -> {
         if (!lvl.getBlockState(plantAt).isAir()) {
            throw new RuntimeException("farmland above is occupied at " + plantAt.toShortString());
         }
         int slot = -1;
         var inv2 = v.getInventory();
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            if (inv2.getItem(i).getItem() == seedF) { slot = i; break; }
         }
         if (slot < 0) throw new RuntimeException("seeds gone before planting");
         var stack = inv2.getItem(slot);
         if (seedF instanceof BlockItem bi) {
            lvl.setBlockAndUpdate(plantAt, bi.getBlock().defaultBlockState());
            stack.shrink(1);
            if (stack.isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
            return "planted " + StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(seedF).toString())
               + " at " + plantAt.toShortString();
         }
         throw new RuntimeException("item " + seedF + " is not a placeable block");
      });
   }
}

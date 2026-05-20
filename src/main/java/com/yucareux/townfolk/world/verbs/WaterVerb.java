package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;

/**
 * {@code [ACTION: water]} — find the closest dry farmland in range
 * (8 blocks, moisture &lt; 7), confirm no natural water within 4
 * blocks horizontal × 2 up, pick a viable tile adjacent to the
 * farmland (preferring air, then replaceable, then dirt/grass), and
 * place a water source. Excavates first if the target tile holds a
 * breakable block.
 *
 * <p>Extracted from {@code ToolDispatcher.doWater} in stage 17a.5.
 */
public final class WaterVerb {

   private WaterVerb() {}

   public static void run(VerbContext ctx) {
      ServerLevel level = ctx.level();
      BlockPos farmland = BlockTaskHelpers.findClosestPos(level, ctx.actor().blockPosition(), 8, pos -> {
         var s = level.getBlockState(pos);
         if (!s.is(Blocks.FARMLAND)) return false;
         return s.getValue(FarmBlock.MOISTURE) < 7;
      });
      if (farmland == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), level.getGameTime(),
            "water — no dry farmland nearby");
         return;
      }
      if (waterWithinReach(level, farmland, 4)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), level.getGameTime(),
            "water — farmland at " + farmland.toShortString()
               + " is already in reach of natural water");
         return;
      }
      BlockPos waterPos = pickWaterSourceTile(level, farmland);
      if (waterPos == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), level.getGameTime(),
            "water — no viable spot to place a source near " + farmland.toShortString());
         return;
      }
      BlockTaskHelpers.enqueueBlockTask(level, ctx.actor(), waterPos, "water", (lvl, v, pos) -> {
         var existing = lvl.getBlockState(pos);
         String breakNote = "";
         if (!existing.isAir() && !existing.is(BlockTags.REPLACEABLE)) {
            var drops = Block.getDrops(existing, lvl, pos, null, v, v.getMainHandItem());
            lvl.destroyBlock(pos, false, v);
            for (var d : drops) {
               if (d.isEmpty()) continue;
               ItemStack leftover = v.getInventory().addItem(d);
               if (leftover != null && !leftover.isEmpty()) {
                  var ent = new ItemEntity(lvl, v.getX(), v.getY(), v.getZ(), leftover);
                  ent.setPickUpDelay(20);
                  lvl.addFreshEntity(ent);
               }
            }
            breakNote = " (excavated first)";
         }
         lvl.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
         return "placed a water source at " + pos.toShortString() + breakNote;
      });
   }

   /** True if any WATER block exists within {@code horizontalRadius} of
    *  {@code farmland}, at the same Y or one above (matches vanilla
    *  farmland hydration rules). */
   private static boolean waterWithinReach(ServerLevel level, BlockPos farmland, int horizontalRadius) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
         for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
            for (int dy = 0; dy <= 1; dy++) {
               cur.set(farmland.getX() + dx, farmland.getY() + dy, farmland.getZ() + dz);
               if (level.getFluidState(cur).is(FluidTags.WATER)) return true;
            }
         }
      }
      return false;
   }

   /** Pick a tile to flood. Ideal: 1 block away from farmland, same Y,
    *  air-filled. Fallback: 2 blocks away, or replaceable / dirt /
    *  grass tiles. Refuses stone/etc — too valuable to break. */
   private static BlockPos pickWaterSourceTile(ServerLevel level, BlockPos farmland) {
      int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}};
      BlockPos best = null;
      double bestRank = Double.MAX_VALUE;
      for (int[] o : offsets) {
         BlockPos p = farmland.offset(o[0], 0, o[1]);
         var s = level.getBlockState(p);
         var below = level.getBlockState(p.below());
         if (below.isAir()) continue;
         double rank;
         if (s.isAir()) rank = 0;
         else if (s.is(BlockTags.REPLACEABLE)) rank = 1;
         else if (s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK)) rank = 2;
         else continue;
         if (rank < bestRank) { bestRank = rank; best = p; }
      }
      return best;
   }
}

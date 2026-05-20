package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code [ACTION: till]} — requires a hoe; find the closest grass /
 * dirt / coarse-dirt block within 8 blocks that has air above, and
 * queue a setBlock to FARMLAND.
 *
 * <p>Uses {@link BlockTaskHelpers#findClosestPos} (not findClosest)
 * because the predicate needs to consult the block above for the
 * air check — findClosest only sees the BlockState, not its position.
 *
 * <p>Extracted from {@code ToolDispatcher.doTill} in stage 17a.4.
 */
public final class TillVerb {

   private TillVerb() {}

   public static void run(VerbContext ctx) {
      if (!BlockTaskHelpers.hasToolWithTag(ctx.actor(), ItemTags.HOES)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "till — I need a hoe to break up the soil");
         return;
      }
      BlockPos target = BlockTaskHelpers.findClosestPos(ctx.level(), ctx.actor().blockPosition(), 8, pos -> {
         var s = ctx.level().getBlockState(pos);
         if (!s.is(Blocks.GRASS_BLOCK) && !s.is(Blocks.DIRT) && !s.is(Blocks.COARSE_DIRT)) return false;
         return ctx.level().getBlockState(pos.above()).isAir();
      });
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "till — no tillable soil within 8 blocks");
         return;
      }
      BlockTaskHelpers.enqueueBlockTask(ctx.level(), ctx.actor(), target, "till", (lvl, v, pos) -> {
         lvl.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
         return "tilled the soil at " + pos.toShortString();
      });
   }
}

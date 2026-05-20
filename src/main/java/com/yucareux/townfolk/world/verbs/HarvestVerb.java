package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;

/**
 * {@code [ACTION: harvest [crop-hint]]} — find the closest ripe
 * crop within 8 blocks (CropBlock at max age, or NetherWart at
 * age=3) and queue a break-and-collect block task. Hint filters by
 * blockstate string match.
 *
 * <p>Extracted from {@code ToolDispatcher.doHarvest} in stage 17a.4.
 */
public final class HarvestVerb {

   private HarvestVerb() {}

   public static void run(VerbContext ctx) {
      String h = ctx.body().toLowerCase(Locale.ROOT);
      BlockPos target = BlockTaskHelpers.findClosest(ctx.level(), ctx.actor().blockPosition(), 8, state -> {
         var b = state.getBlock();
         if (b instanceof CropBlock crop) {
            if (!h.isEmpty() && !state.toString().toLowerCase(Locale.ROOT).contains(h)) return false;
            return crop.isMaxAge(state);
         }
         if (b instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) == 3;
         }
         return false;
      });
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "harvest — no ripe crop within 8 blocks"
               + (h.isEmpty() ? "" : " matching \"" + ctx.body() + "\""));
         return;
      }
      BlockTaskHelpers.enqueueBlockTask(ctx.level(), ctx.actor(), target, "harvest",
         (lvl, v, pos) -> BlockTaskHelpers.breakAndCollect(lvl, v, pos, "harvested"));
   }
}

package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;

/**
 * {@code [ACTION: mine [ore-hint]]} — requires a pickaxe; find the
 * closest {@code BlockTags.MINEABLE_WITH_PICKAXE} block within 10
 * blocks (hint filters by block-id substring) and queue break-and-
 * collect.
 *
 * <p>Extracted from {@code ToolDispatcher.doMine} in stage 17a.4.
 */
public final class MineVerb {

   private MineVerb() {}

   public static void run(VerbContext ctx) {
      if (!BlockTaskHelpers.hasToolWithTag(ctx.actor(), ItemTags.PICKAXES)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "mine — I need a pickaxe to mine that");
         return;
      }
      String h = ctx.body().toLowerCase(Locale.ROOT);
      BlockPos target = BlockTaskHelpers.findClosest(ctx.level(), ctx.actor().blockPosition(), 10, state -> {
         if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return false;
         if (h.isEmpty()) return true;
         var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
         return id != null && id.getPath().contains(h);
      });
      if (target == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "mine — no matching ore/stone within 10 blocks");
         return;
      }
      BlockTaskHelpers.enqueueBlockTask(ctx.level(), ctx.actor(), target, "mine",
         (lvl, v, pos) -> BlockTaskHelpers.breakAndCollect(lvl, v, pos, "mined"));
   }
}

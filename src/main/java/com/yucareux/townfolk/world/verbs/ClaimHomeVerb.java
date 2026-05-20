package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;

/**
 * {@code [ACTION: claim_home]} / {@code [ACTION: claim_bed]} —
 * scan around the villager (radius 6) and the nearest player
 * (radius 4, within 12 blocks) for a bed block. The closest hit
 * becomes the villager's {@code HOME} memory + their
 * {@code playerSetHome} flag flips true so the {@code [need:home]}
 * todo closes properly.
 *
 * <p>Extracted from {@code ToolDispatcher.doClaimHome} in stage 16b.2.b.
 */
public final class ClaimHomeVerb {

   private ClaimHomeVerb() {}

   public static void run(VerbContext ctx) {
      BlockPos bedPos = VerbUtils.findBlock(ctx.level(), ctx.actor().blockPosition(), 6,
         state -> state.is(BlockTags.BEDS));
      if (bedPos == null) {
         Player p = ctx.level().getNearestPlayer(ctx.actor(), 12.0);
         if (p != null) {
            bedPos = VerbUtils.findBlock(ctx.level(), p.blockPosition(), 4,
               state -> state.is(BlockTags.BEDS));
         }
      }
      if (bedPos == null) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=claim_home_no_bed", "");
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " looked for a bed but didn't find one nearby");
         return;
      }
      ctx.actor().getBrain().setMemory(MemoryModuleType.HOME,
         GlobalPos.of(ctx.level().dimension(), bedPos));
      LlmVillagerComponent cc = ctx.actor().getData(ModRegistries.LLM_VILLAGER.get());
      ctx.actor().setData(ModRegistries.LLM_VILLAGER.get(), cc.withPlayerSetHome(true));
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=home_claimed",
         "pos=" + bedPos.toShortString());
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
         ctx.self().name() + " claimed a bed at " + bedPos.toShortString() + " as home");
   }
}

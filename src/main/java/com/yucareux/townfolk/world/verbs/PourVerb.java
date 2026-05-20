package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;

/**
 * {@code [ACTION: pour <fluid>]} — narrative-only emote verb.
 * Doesn't actually move fluid; records the action as a memory and
 * a town-log line so the LLM has a way to roleplay pouring drinks
 * at the tavern, watering plants ceremonially, etc. without
 * mechanical consequences.
 *
 * <p>Acts as the social-flavour counterpart to the mechanical
 * {@link FillVerb} / {@link DrainVerb}. If the player wants a
 * villager to actually move fluid, the LLM should emit fill/drain.
 * Pour is the "soft" alternative — useful for dialogue ("Klaus
 * pours Anna a tankard") where the actual liquid transfer doesn't
 * need to be simulated.
 *
 * <p>Stage 23 / 20b. Last of the three fluid verbs.
 */
public final class PourVerb {

   private PourVerb() {}

   public static void run(VerbContext ctx) {
      String fluidHint = ctx.body().trim();
      if (fluidHint.isEmpty()) fluidHint = "something";
      long day = ctx.level().getGameTime() / 24000L;
      String msg = ctx.self().name() + " pours " + fluidHint + ".";
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=poured", "fluid=" + fluidHint);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.DIALOGUE, msg);
      MemoryStore.write(ctx.actor(), "social", day,
         "I poured " + fluidHint + ".");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "poured " + fluidHint);
   }
}

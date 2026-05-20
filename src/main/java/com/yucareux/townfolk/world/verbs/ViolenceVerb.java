package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;

/**
 * Capability-scaffolding stub for {@code attack / defend / flee}. The
 * LLM grammar reserves these so personas can roleplay around violence,
 * but the world ships in peaceful mode — actual combat logic isn't
 * wired yet. The persona-side prompt grammar stays stable so when
 * combat lands, only this class changes.
 *
 * <p>Extracted from {@code ToolDispatcher.doViolencePlaceholder} in
 * stage 16b.2.a.
 */
public final class ViolenceVerb {

   private ViolenceVerb() {}

   public static void run(VerbContext ctx) {
      // The full lowercase action ("attack player", "defend home", etc.)
      // is reconstructed from verbKey + body for the log line.
      String action = ctx.body().isEmpty()
         ? ctx.verbKey()
         : (ctx.verbKey() + " " + ctx.body()).toLowerCase(Locale.ROOT);
      boolean peaceful = com.yucareux.townfolk.config.TownfolkConfig.COMMON.peaceful.get();
      long day = ctx.level().getGameTime() / 24000L;
      if (peaceful) {
         String msg = ctx.self().name() + " would " + action + " but the world is set to peaceful";
         VerboseLog.write("ACTION_RESULT", "actor=" + ctx.self().name() + " status=peaceful_stub",
            "verb=" + action);
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
         MemoryStore.write(ctx.actor(), "intent", day,
            "I wanted to " + action + " but stayed my hand — peace holds in our town.");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            action + " — peaceful mode, no combat is possible right now");
         return;
      }
      VerboseLog.write("ACTION_RESULT", "actor=" + ctx.self().name() + " status=combat_not_implemented",
         "verb=" + action);
      ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
         action + " — combat behaviour not yet implemented");
   }
}

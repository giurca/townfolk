package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ReflexService;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;

/**
 * {@code [ACTION: forget <id-or-fuzzy>]} / {@code cancel_rule} /
 * {@code drop_reflex} — remove a standing order. Supports both exact
 * id and fuzzy substring match against the rule's trigger / action
 * (delegated to {@link ReflexService#removeReflex}).
 *
 * <p>Extracted from {@code ToolDispatcher.doForget} in stage 16b.2.b.
 */
public final class ForgetVerb {

   private ForgetVerb() {}

   public static void run(VerbContext ctx) {
      String body = ctx.body();
      if (body.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "forget — needs an id or fuzzy description of the standing order to drop");
         return;
      }
      boolean removed = ReflexService.removeReflex(ctx.actor(), body);
      if (!removed) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "forget — no standing order matches \"" + body + "\"");
         return;
      }
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
         ctx.self().name() + " drops a standing order matching \"" + body + "\"");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "standing order dropped: \"" + body + "\"");
   }
}

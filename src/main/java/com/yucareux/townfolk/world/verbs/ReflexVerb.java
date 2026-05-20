package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ReflexService;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code [ACTION: reflex when=<trigger> do=<action>]} (also
 * {@code standing_order} / {@code rule}) — register a long-lived
 * trigger → action rule with {@link ReflexService}. Supports a
 * comma-separated fallback form ({@code "after harvest, deposit all wheat"})
 * for when the LLM forgets the {@code when=/do=} syntax.
 *
 * <p>Valid triggers are {@code after:<verb>}, {@code phase:<phase>},
 * {@code inv:>=:<item>:<n>}, {@code inv:<=:<item>:<n>} —
 * {@link ReflexService#addReflex} validates.
 *
 * <p>Extracted from {@code ToolDispatcher.doReflex} in stage 16b.2.b.
 */
public final class ReflexVerb {

   /** Preferred form: {@code when=<trigger> do=<action>}. Tolerates a
    *  leading {@code reflex / standing_order / rule} word (already
    *  trimmed by the dispatch entry path). */
   private static final Pattern REFLEX_KV = Pattern.compile(
      "(?i).*?\\bwhen\\s*=\\s*([^\\s].*?)\\s+do\\s*=\\s*(.+)");

   private ReflexVerb() {}

   public static void run(VerbContext ctx) {
      String body = ctx.actionRaw()
         .replaceFirst("(?i)^(reflex|standing_order|rule)\\b\\s*:?\\s*", "")
         .trim();
      String trigger = null, action = null;

      Matcher m = REFLEX_KV.matcher(body);
      if (m.matches()) {
         trigger = m.group(1).trim();
         action = m.group(2).trim();
      } else {
         int comma = body.indexOf(',');
         if (comma > 0) {
            trigger = body.substring(0, comma).trim();
            action = body.substring(comma + 1).trim();
         }
      }
      if (trigger == null || action == null || trigger.isEmpty() || action.isEmpty()) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=reflex_malformed",
            "raw=\"" + ctx.actionRaw() + "\"");
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " malformed reflex: \"" + ctx.actionRaw() + "\"");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "reflex — couldn't parse trigger + action from \"" + ctx.actionRaw()
               + "\". Expected form: reflex when=<trigger> do=<action>");
         return;
      }
      var result = ReflexService.addReflex(ctx.level(), ctx.actor(), trigger, action);
      if (result.isEmpty()) {
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " rejected reflex: trigger \"" + trigger + "\" is not recognised");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "reflex — trigger \"" + trigger + "\" not recognised. Valid: after:<verb>, "
               + "phase:<phase>, inv:>=:<item>:<n>, inv:<=:<item>:<n>");
         return;
      }
      String msg = ctx.self().name() + " adopts a standing order: when " + result.get().trigger()
         + " then " + result.get().action();
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=reflex_added",
         "id=" + result.get().id() + " when=" + result.get().trigger()
            + " do=" + result.get().action());
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "standing order set: when " + result.get().trigger() + " then " + result.get().action());
      MemoryStore.write(ctx.actor(), "reflex",
         ctx.level().getGameTime() / 24000L,
         "I gave myself a standing order — when " + result.get().trigger()
            + ", I now " + result.get().action() + ".");
   }
}

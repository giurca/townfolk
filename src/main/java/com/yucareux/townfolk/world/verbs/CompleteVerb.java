package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.villager.Todo;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.ArrayList;
import java.util.Locale;

/**
 * {@code [ACTION: completed: <description>]} / {@code [ACTION: done: …]}
 * — fuzzy-match the description against the actor's open todos and
 * mark the best one done. Score floor of 3 word-overlap matches
 * prevents a wild guess from closing the wrong commitment.
 *
 * <p>Extracted from {@code ToolDispatcher.doComplete} in stage 16b.2.b.
 */
public final class CompleteVerb {

   private CompleteVerb() {}

   public static void run(VerbContext ctx) {
      LlmVillagerComponent c = ctx.actor().getData(ModRegistries.LLM_VILLAGER.get());
      String what = ctx.body();
      if (c.todos().isEmpty() || what.isEmpty()) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=complete_noop", "what=\"" + what + "\"");
         return;
      }
      String needle = what.toLowerCase(Locale.ROOT);
      Todo best = null;
      int bestScore = 0;
      for (var t : c.todos()) {
         if (!t.isOpen()) continue;
         int s = VerbUtils.fuzzyOverlap(needle, t.text().toLowerCase(Locale.ROOT));
         if (s > bestScore) { bestScore = s; best = t; }
      }
      if (best == null || bestScore < 3) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=complete_no_match",
            "what=\"" + what + "\" bestScore=" + bestScore);
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " marks something done but I can't find the todo: \"" + what + "\"");
         return;
      }
      var updated = best.withStatus("done");
      var nextTodos = new ArrayList<>(c.todos());
      for (int i = 0; i < nextTodos.size(); i++) {
         if (nextTodos.get(i).id().equals(best.id())) { nextTodos.set(i, updated); break; }
      }
      long day = ctx.level().getGameTime() / 24000L;
      ctx.actor().setData(ModRegistries.LLM_VILLAGER.get(), c.withTodos(nextTodos));
      MemoryStore.write(ctx.actor(), "action", day, "I finished: " + best.text());
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=completed", "todo=\"" + best.text() + "\"");
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
         ctx.self().name() + " ✓ completed: " + best.text());
   }
}

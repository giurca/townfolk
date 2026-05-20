package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.villager.Todo;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.world.entity.player.Player;

/**
 * {@code [ACTION: remember <text>]} / {@code [ACTION: commit <text>]}
 * — append a new open commitment to the villager's todo list, with
 * the nearest player (within 16 blocks) as the counterparty. The
 * todo surfaces in every subsequent dialogue + autonomy prompt under
 * OPEN COMMITMENTS and can be closed via {@code [ACTION: completed: …]}.
 *
 * <p>Duplicate-ish open todos (same lowercase text) are deduped so
 * the LLM repeating itself doesn't pile near-identical entries.
 *
 * <p>Extracted from {@code ToolDispatcher.doRemember} in stage 16b.2.a.
 */
public final class RememberVerb {

   private RememberVerb() {}

   public static void run(VerbContext ctx) {
      String text = ctx.body();
      if (text.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "remember — empty commitment text");
         return;
      }
      long day = ctx.level().getGameTime() / 24000L;
      Player p = ctx.level().getNearestPlayer(ctx.actor(), 16.0);
      String counterparty = p == null ? "" : p.getName().getString();

      LlmVillagerComponent c = ctx.actor().getData(ModRegistries.LLM_VILLAGER.get());

      String key = text.toLowerCase(Locale.ROOT);
      for (var existing : c.todos()) {
         if (existing.isOpen()
             && existing.text().toLowerCase(Locale.ROOT).equals(key)) {
            VerboseLog.write("ACTION_RESULT",
               "actor=" + ctx.self().name() + " status=remember_dup",
               "text=" + text);
            ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
               "already had that on my list (\"" + text + "\")");
            return;
         }
      }

      Todo todo = new Todo(UUID.randomUUID().toString(), text, counterparty, "open", day);
      ctx.actor().setData(ModRegistries.LLM_VILLAGER.get(), c.withAppendedTodo(todo));

      String summary = ctx.self().name() + " noted a commitment"
         + (counterparty.isEmpty() ? "" : " for " + counterparty) + ": " + text;
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=remembered",
         "text=" + text + " with=" + counterparty);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, summary);
      MemoryStore.write(ctx.actor(), "commitment", day,
         "I promised" + (counterparty.isEmpty() ? "" : " " + counterparty)
            + ": " + text);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "added to todos: \"" + text + "\""
            + (counterparty.isEmpty() ? "" : " (for " + counterparty + ")"));
   }
}

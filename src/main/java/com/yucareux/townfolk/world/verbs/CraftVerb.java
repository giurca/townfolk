package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.RecipeCatalog;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: craft <recipe>]} — vanilla {@link net.minecraft.world.item.crafting.RecipeManager}
 * lookup via {@link RecipeCatalog}. Tolerates LLM noise prefixes
 * ("a loaf of bread", "some sticks") by stripping common articles
 * and quantifiers before searching.
 *
 * <p>On match, the recipe's ingredient slots are subtracted from the
 * villager's inventory and the result is added (overflow drops at
 * the actor's feet). Failure modes are surfaced via
 * {@link ActionFeedback} with the specific missing ingredients so
 * the LLM can react.
 *
 * <p>Extracted from {@code ToolDispatcher.doCraft} in stage 17a.2.
 */
public final class CraftVerb {

   private CraftVerb() {}

   public static void run(VerbContext ctx) {
      String key = ctx.body().toLowerCase(Locale.ROOT).trim();
      key = key.replaceAll("^(a|an|some|one|two|three|four|five|loaf of|piece of)\\s+", "").trim();

      var holderOpt = RecipeCatalog.findByOutputName(ctx.level(), key);
      if (holderOpt.isEmpty()) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=craft_unknown",
            "recipe=\"" + ctx.body() + "\"");
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " doesn't know how to craft \"" + ctx.body() + "\"");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "craft \"" + ctx.body() + "\" — no recipe found");
         return;
      }
      var holder = holderOpt.get();
      var inv = ctx.actor().getInventory();
      var attempt = RecipeCatalog.tryCraft(ctx.level(), inv, holder);
      if (!attempt.ok()) {
         StringBuilder miss = new StringBuilder();
         for (var e : attempt.missing().entrySet()) {
            if (miss.length() > 0) miss.append(", ");
            miss.append(e.getValue()).append("× ").append(StorageHelpers.shortName(e.getKey()));
         }
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=craft_missing",
            "missing=" + miss);
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " tried to craft " + key + " but needs " + miss);
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "craft " + key + " — missing " + miss);
         return;
      }
      if (!attempt.leftover().isEmpty()) {
         ItemEntity drop = new ItemEntity(ctx.level(),
            ctx.actor().getX(), ctx.actor().getY(), ctx.actor().getZ(), attempt.leftover());
         drop.setPickUpDelay(20);
         ctx.level().addFreshEntity(drop);
      }
      ItemStack out = attempt.produced();
      String outName = StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(out.getItem()).toString());
      long day = ctx.level().getGameTime() / 24000L;
      String msg = ctx.self().name() + " crafted " + out.getCount() + "× " + outName;
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=crafted",
         "recipe=" + key + " out=" + out.getCount() + "× " + outName);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
      MemoryStore.write(ctx.actor(), "craft", day,
         "I crafted " + out.getCount() + "× " + outName);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "crafted " + out.getCount() + "× " + outName);
   }
}

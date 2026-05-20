package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.RecipeCatalog;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: eat]} / {@code [ACTION: eat <hint>]} — find the
 * highest-nutrition food item in the villager's bag (optionally
 * filtered by {@code hint} resolved through {@link RecipeCatalog}),
 * consume one, heal a small amount proportional to nutrition. Used
 * for self-care + survival flavor; meal-windows in
 * {@code MealService} handle scheduled meals.
 *
 * <p>Extracted from {@code ToolDispatcher.doEat} in stage 17a.2.
 */
public final class EatVerb {

   private EatVerb() {}

   public static void run(VerbContext ctx) {
      String hint = ctx.body();
      var inv = ctx.actor().getInventory();
      int bestSlot = -1;
      int bestNutrition = -1;
      Item hintItem = hint.isEmpty() ? null : RecipeCatalog.resolveItemOpt(hint).orElse(null);
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (s.isEmpty()) continue;
         var food = s.getFoodProperties(ctx.actor());
         if (food == null) continue;
         if (hintItem != null && s.getItem() != hintItem) continue;
         int n = food.nutrition();
         if (n > bestNutrition) { bestNutrition = n; bestSlot = i; }
      }
      if (bestSlot < 0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "eat — no edible item in your bag"
               + (hint.isEmpty() ? "" : " matching \"" + hint + "\""));
         return;
      }
      var stack = inv.getItem(bestSlot);
      String name = StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
      var food = stack.getFoodProperties(ctx.actor());
      stack.shrink(1);
      if (stack.isEmpty()) inv.setItem(bestSlot, ItemStack.EMPTY);

      float heal = Math.min(ctx.actor().getMaxHealth() - ctx.actor().getHealth(),
         Math.max(1f, (food == null ? 1 : food.nutrition()) * 0.5f));
      if (heal > 0) ctx.actor().heal(heal);

      long day = ctx.level().getGameTime() / 24000L;
      String summary = ctx.self().name() + " ate 1× " + name;
      VerboseLog.write("ACTION_RESULT", "actor=" + ctx.self().name() + " status=ate", summary);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, summary);
      MemoryStore.write(ctx.actor(), "eat", day, "I ate a " + name + " — felt restored.");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "ate 1× " + name + " (healed " + (int) heal + " HP)");
   }
}

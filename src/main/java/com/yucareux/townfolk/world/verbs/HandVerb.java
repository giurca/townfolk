package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.world.RecipeCatalog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.regex.Matcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: hand [N] <item> to <player>]} — transfer N of
 * {@code item} from the villager's bag into the named player's
 * inventory (overflow drops at the player's feet). Resolves the
 * player by name match against {@code level.players()}, falling
 * back to the nearest player within 16 blocks.
 *
 * <p>Unlike {@link GiveVerb}, this verb DOES decrement the villager's
 * inventory — it's the canonical player-handoff path.
 *
 * <p>Extracted from {@code ToolDispatcher.doHand} in stage 17a.1.
 */
public final class HandVerb {

   private HandVerb() {}

   public static void run(VerbContext ctx) {
      Matcher m = StorageHelpers.HAND_PATTERN.matcher(ctx.body());
      if (!m.matches()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "hand — malformed (expected \"hand [N] <item> to <player>\")");
         return;
      }
      int qty = m.group(1) == null ? 1
               : "all".equalsIgnoreCase(m.group(1)) ? Integer.MAX_VALUE
               : Math.max(1, Math.min(64, Integer.parseInt(m.group(1))));
      String itemTok = m.group(2).trim();
      String playerTok = m.group(3).trim().toLowerCase(Locale.ROOT);

      Item item = RecipeCatalog.resolveItemOpt(itemTok).orElse(null);
      if (item == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "hand — unknown item \"" + itemTok + "\"");
         return;
      }

      Player target = null;
      Player nearest = ctx.level().getNearestPlayer(ctx.actor(), 16.0);
      if (playerTok.contains("me") || playerTok.contains("player") || playerTok.contains("you")) {
         target = nearest;
      } else {
         for (var p : ctx.level().players()) {
            if (playerTok.contains(p.getName().getString().toLowerCase(Locale.ROOT))) {
               target = p; break;
            }
         }
         if (target == null) target = nearest;
      }
      if (target == null || ctx.actor().distanceToSqr(target) > 144.0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "hand — no player close enough");
         return;
      }

      var inv = ctx.actor().getInventory();
      int have = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (s.getItem() == item) have += s.getCount();
      }
      int give = Math.min(qty, have);
      if (give <= 0) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "hand " + itemTok + " — none in your bag");
         return;
      }

      ItemStack stack = new ItemStack(item, give);
      int need = give;
      for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
         var s = inv.getItem(i);
         if (s.getItem() != item) continue;
         int take = Math.min(s.getCount(), need);
         s.shrink(take);
         need -= take;
         if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
      }
      if (!target.addItem(stack)) {
         ItemEntity drop = new ItemEntity(ctx.level(),
            target.getX(), target.getY(), target.getZ(), stack);
         drop.setPickUpDelay(0);
         ctx.level().addFreshEntity(drop);
      }
      long day = ctx.level().getGameTime() / 24000L;
      String itemName = StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(item).toString());
      String summary = ctx.self().name() + " handed " + give + "× " + itemName
         + " to " + target.getName().getString();
      VerboseLog.write("ACTION_RESULT", "actor=" + ctx.self().name() + " status=handed", summary);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, summary);
      MemoryStore.write(ctx.actor(), "give", day,
         "I handed " + give + "× " + itemName + " to " + target.getName().getString() + ".");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "handed " + give + "× " + itemName + " to " + target.getName().getString());
   }
}

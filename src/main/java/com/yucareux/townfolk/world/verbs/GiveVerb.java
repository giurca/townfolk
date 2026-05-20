package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.regex.Matcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: give <name>: <count> <item>]} — drop the requested
 * stack near the named recipient (villager or, by name match,
 * player) or at the actor's feet if the name doesn't resolve. The
 * villager's own inventory is NOT decremented — this is the persona-
 * roleplay "gift" verb, not the {@code hand} verb. (Hand actually
 * transfers; give is a thin RP convenience for backstory beats.)
 *
 * <p>Extracted from {@code ToolDispatcher.doGive} in stage 17a.1.
 */
public final class GiveVerb {

   private GiveVerb() {}

   public static void run(VerbContext ctx) {
      Matcher m = StorageHelpers.GIVE.matcher(ctx.body());
      if (!m.matches()) {
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " malformed give: " + ctx.body());
         return;
      }
      String targetName = m.group(1).trim();
      int count = m.group(2) == null ? 1
         : Math.max(1, Math.min(64, Integer.parseInt(m.group(2))));
      String itemName = m.group(3).trim().toLowerCase(Locale.ROOT).replace(' ', '_');

      Item item = StorageHelpers.resolveItemFlexibly(itemName);
      if (item == null) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + ctx.self().name() + " status=unknown_item", "item=" + itemName);
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " can't give unknown item: " + itemName);
         return;
      }

      TownData data = ctx.town().getTown();
      VillagerEntry recipient = null;
      for (VillagerEntry e : data.villagers()) {
         if (e.name().equalsIgnoreCase(targetName)) { recipient = e; break; }
      }
      Entity recipientEnt = recipient == null ? null : ctx.level().getEntity(recipient.uuid());

      BlockPos dropPos = recipientEnt == null ? ctx.actor().blockPosition() : recipientEnt.blockPosition();
      ItemStack stack = new ItemStack(item, count);
      ItemEntity drop = new ItemEntity(ctx.level(),
         dropPos.getX() + 0.5, dropPos.getY() + 0.5, dropPos.getZ() + 0.5, stack);
      drop.setPickUpDelay(20);
      ctx.level().addFreshEntity(drop);

      String summary = ctx.self().name() + " dropped " + count + "× "
         + item.getDescription().getString()
         + " for " + (recipient == null ? targetName : recipient.name())
         + " at " + dropPos.toShortString();
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=gave", summary);
      data.log().add(ctx.level().getGameTime(), TownLog.Level.INFO, summary);
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "gave " + count + "× " + item.getDescription().getString()
            + " to " + (recipient == null ? targetName : recipient.name()));
   }
}

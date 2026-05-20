package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.ContainerAdapters;
import com.yucareux.townfolk.town.StorageConfig;
import com.yucareux.townfolk.town.StorageIndex;
import com.yucareux.townfolk.town.StorageRegistry;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.TownTreasury;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.BlockTaskQueue;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import java.util.regex.Matcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: deposit <qty> <item> [in <name>]]} /
 * {@code [ACTION: withdraw <qty> <item> [from <name>]]} — move items
 * between the villager's bag and the nearest USABLE registered
 * container (or a labelled one if the LLM hints with
 * {@code "...in/from/to <name>"}).
 *
 * <p>Routing in three phases:
 * <ol>
 *   <li><b>Label hint resolution</b> — if the body had
 *       {@code " in <name>"}/{@code " from <name>"}/{@code " to <name>"},
 *       try {@link StorageRegistry#findByLabel} first.
 *   <li><b>In-range usable scan</b> — {@link StorageIndex#nearestMatching}
 *       within 6 blocks, filtered by deposit-acceptance or
 *       contains-item.
 *   <li><b>Out-of-range routing</b> — for the in-range failure case,
 *       queue a walk to {@link TownTreasury#findNearestForDeposit}
 *       (or {@link TownTreasury#findNearestWith}) and re-invoke the
 *       verb on arrival.
 * </ol>
 *
 * <p>Extracted from {@code ToolDispatcher.doStorage} in stage 17a.3.
 * Deposit and withdraw share the same body via {@link #runImpl} —
 * the dispatch table wires {@link #runDeposit} and
 * {@link #runWithdraw} as the two public entry points.
 */
public final class StorageVerb {

   private StorageVerb() {}

   public static void runDeposit(VerbContext ctx) { runImpl(ctx, true); }
   public static void runWithdraw(VerbContext ctx) { runImpl(ctx, false); }

   private static void runImpl(VerbContext ctx, boolean depositing) {
      ServerLevel level = ctx.level();
      TownSquareBlockEntity town = ctx.town();
      Villager actor = ctx.actor();
      VillagerEntry self = ctx.self();
      String body = ctx.body();

      String b = body.replaceFirst("^[:\\s]+", "").trim();
      // Trailing " in <name>" / " from <name>" / " to <name>" is the
      // optional barrel-label hint — preserve it so we can route to
      // the labelled barrel by name instead of generic item-nearest.
      String labelHint = "";
      int inIdx   = b.toLowerCase(Locale.ROOT).indexOf(" in ");
      int fromIdx = b.toLowerCase(Locale.ROOT).indexOf(" from ");
      int toIdx   = b.toLowerCase(Locale.ROOT).indexOf(" to ");
      int cut = inIdx;
      int cutLen = 4;
      if (fromIdx >= 0 && (cut < 0 || fromIdx < cut)) { cut = fromIdx; cutLen = 6; }
      if (toIdx   >= 0 && (cut < 0 || toIdx   < cut)) { cut = toIdx;   cutLen = 4; }
      if (cut >= 0) {
         labelHint = b.substring(cut + cutLen).trim();
         b = b.substring(0, cut).trim();
      }
      Matcher m = StorageHelpers.QTY_ITEM.matcher(b);
      if (!m.matches()) {
         VerboseLog.write("ACTION_RESULT",
            "actor=" + self.name() + " status=storage_malformed",
            "body=\"" + body + "\"");
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit" : "withdraw") + " — malformed (\"" + body + "\")");
         return;
      }
      String qtyTok = m.group(1);
      String itemTok = m.group(2).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
      int qty = "all".equalsIgnoreCase(qtyTok) ? Integer.MAX_VALUE
              : qtyTok == null ? 1
              : Math.max(1, Math.min(256, Integer.parseInt(qtyTok)));

      Item item = StorageHelpers.resolveItemFlexibly(itemTok);
      ResourceLocation itemId = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
      VerboseLog.write("STORAGE_DISPATCH",
         "actor=" + self.name() + " verb=" + (depositing ? "deposit" : "withdraw")
            + " qty=" + qty + " item=" + itemId
            + " labelHint=\"" + labelHint + "\"",
         "body=\"" + body + "\"");
      if (item == null) {
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
            self.name() + " doesn't recognise item: " + itemTok);
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit" : "withdraw") + " — unknown item \"" + itemTok + "\"");
         VerboseLog.write("STORAGE_REJECT",
            "actor=" + self.name() + " reason=unknown-item itemTok=" + itemTok, "");
         return;
      }

      // EXPLICIT BARREL-BY-NAME routing.
      StorageIndex.Hit hit = null;
      BlockPos labelPos = null;
      if (!labelHint.isEmpty()) {
         labelPos = StorageRegistry.findByLabel(level, labelHint, actor.blockPosition());
         VerboseLog.write("STORAGE_LABEL_LOOKUP",
            "actor=" + self.name() + " hint=\"" + labelHint + "\""
               + " result=" + (labelPos == null ? "null" : labelPos.toShortString()), "");
         if (labelPos == null) {
            ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
               (depositing ? "deposit" : "withdraw")
                  + " — no barrel named \"" + labelHint + "\"");
            VerboseLog.write("STORAGE_REJECT",
               "actor=" + self.name() + " reason=no-such-label hint=\"" + labelHint + "\"", "");
            return;
         }
         double dsq = labelPos.distSqr(actor.blockPosition());
         if (dsq <= 6 * 6) {
            var c = ContainerAdapters.at(level, labelPos);
            VerboseLog.write("STORAGE_LABEL_INRANGE",
               "actor=" + self.name() + " labelPos=" + labelPos.toShortString()
                  + " distSq=" + String.format(Locale.ROOT, "%.2f", dsq)
                  + " hasHandler=" + (c != null),
               "");
            if (c != null) {
               hit = new StorageIndex.Hit(labelPos, c);
            }
         } else {
            VerboseLog.write("STORAGE_LABEL_OUTRANGE",
               "actor=" + self.name() + " labelPos=" + labelPos.toShortString()
                  + " distSq=" + String.format(Locale.ROOT, "%.2f", dsq),
               "");
         }
      }

      // In-range usable scan.
      final boolean depositingFinalForPred = depositing;
      final Item itemFinal = item;
      if (hit == null) hit = StorageIndex.nearestMatching(
         level, actor.blockPosition(), 6,
         candidate -> {
            var cfg = StorageRegistry.find(level, candidate.pos());
            if (cfg == null) return false;
            if (depositingFinalForPred) return cfg.acceptsDeposit(new ItemStack(itemFinal));
            var cc = candidate.container();
            for (int i = 0; i < cc.getContainerSize(); i++) {
               if (cc.getItem(i).getItem() == itemFinal) return true;
            }
            return false;
         });
      boolean inRangeUsable = hit != null;
      @SuppressWarnings("unused")
      StorageConfig storageCfg = hit == null ? null : StorageRegistry.find(level, hit.pos());

      if (!inRangeUsable) {
         var fallbackHit = StorageIndex.nearest(level, actor.blockPosition(), 6);
         if (fallbackHit != null) {
            var cfg = StorageRegistry.find(level, fallbackHit.pos());
            String reason;
            if (cfg == null) reason = "unregistered";
            else if (depositing) reason = "filter-rejects";
            else reason = "no-item";
            VerboseLog.write("STORAGE_NEAREST_UNUSABLE",
               "actor=" + self.name() + " pos=" + fallbackHit.pos().toShortString()
                  + " item=" + itemId + " verb=" + (depositing ? "deposit" : "withdraw")
                  + " reason=" + reason,
               "no usable barrel within 6 blocks — falling through to treasury routing");
         }
      }

      if (!inRangeUsable) {
         // Out-of-range routing.
         BlockPos containerPos;
         if (labelPos != null) {
            containerPos = labelPos;
            VerboseLog.write("STORAGE_NAVIGATE_BY_LABEL",
               "actor=" + self.name() + " pos=" + containerPos.toShortString()
                  + " hint=\"" + labelHint + "\"", "");
         } else {
            var target = depositing
               ? TownTreasury.findNearestForDeposit(level, actor.blockPosition(), item)
               : TownTreasury.findNearestWith(level, actor.blockPosition(), item);
            if (target.isEmpty()) {
               ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
                  (depositing ? "deposit" : "withdraw") + " — no registered container in town"
                     + (depositing ? " accepts " + StorageHelpers.shortName(itemId.toString())
                                   : " has "     + StorageHelpers.shortName(itemId.toString())));
               return;
            }
            containerPos = target.get().pos();
         }
         final String verbBody = body;
         final boolean depositingFinal = depositing;
         BlockTaskQueue.enqueue(level, actor, new BlockTaskQueue.BlockTask(
            actor.getUUID(), containerPos,
            level.getGameTime() + 20L * 60,
            depositingFinal ? "deposit" : "withdraw",
            (lvl, v, p) -> {
               // Re-fire the verb on arrival; reuse the parsed body so
               // the label hint + qty + item are preserved exactly.
               VerbContext arrived = new VerbContext(lvl, town, v, self,
                  depositingFinal ? "deposit" : "withdraw", verbBody, verbBody);
               runImpl(arrived, depositingFinal);
               return (depositingFinal ? "delivered " : "fetched ")
                  + StorageHelpers.shortName(itemId.toString()) + " at " + p.toShortString();
            }));
         return;
      }

      // In-range barrel IS usable.
      Container container = hit.container();
      BlockPos cpos = hit.pos();

      var inv = actor.getInventory();
      int available;
      if (depositing) {
         available = 0;
         for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == item) available += s.getCount();
         }
      } else {
         available = 0;
         for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (s.getItem() == item) available += s.getCount();
         }
      }
      if (available <= 0) {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            (depositing ? "deposit " : "withdraw ") + StorageHelpers.shortName(itemId.toString())
               + (depositing ? " — none in your bag" : " — none in the container"));
         VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status="
            + (depositing ? "deposit_none" : "withdraw_none"),
            "item=" + itemId.toString());
         return;
      }

      StorageHelpers.playContainerSound(level, cpos, true);

      int moved = 0;
      if (depositing) {
         int want = qty;
         for (int i = 0; i < inv.getContainerSize() && want > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(s.getCount(), want);
            ItemStack toMove = s.copy();
            toMove.setCount(take);
            ItemStack leftover = StorageHelpers.addToContainer(container, toMove);
            int placed = take - (leftover == null ? 0 : leftover.getCount());
            s.shrink(placed);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            moved += placed;
            want -= placed;
            if (leftover != null && !leftover.isEmpty()) break;
         }
      } else {
         int want = qty;
         for (int i = 0; i < container.getContainerSize() && want > 0; i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int take = Math.min(s.getCount(), want);
            ItemStack toMove = s.copy();
            toMove.setCount(take);
            ItemStack leftover = inv.addItem(toMove);
            int placed = take - (leftover == null ? 0 : leftover.getCount());
            s.shrink(placed);
            if (s.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            moved += placed;
            want -= placed;
            if (leftover != null && !leftover.isEmpty()) break;
         }
      }
      container.setChanged();
      StorageHelpers.playContainerSound(level, cpos, false);

      StorageRegistry.touch(level, cpos, self.name(), level.getGameTime());
      long day = level.getGameTime() / 24000L;
      String verb = depositing ? "deposited" : "withdrew";
      String msg = self.name() + " " + verb + " " + moved + "× "
         + StorageHelpers.shortName(itemId.toString())
         + " " + (depositing ? "into" : "from") + " container at " + cpos.toShortString();
      VerboseLog.write("ACTION_RESULT", "actor=" + self.name() + " status=" + verb, msg);
      if (moved > 0) {
         town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, msg);
         String containerRef = StorageHelpers.containerRefFor(level, cpos);
         MemoryStore.write(actor, "storage", day,
            "I " + verb + " " + moved + "× " + StorageHelpers.shortName(itemId.toString())
            + " " + (depositing ? "into" : "from") + " " + containerRef + ".");
         ActionFeedback.recordOk(actor.getUUID(), level.getGameTime(),
            verb + " " + moved + "× " + StorageHelpers.shortName(itemId.toString())
            + " " + (depositing ? "into" : "from") + " " + containerRef);
      } else {
         ActionFeedback.recordFail(actor.getUUID(), level.getGameTime(),
            verb + " " + StorageHelpers.shortName(itemId.toString())
            + (depositing
               ? " — none of that in your inventory"
               : " — none of that in the container"));
      }
   }
}

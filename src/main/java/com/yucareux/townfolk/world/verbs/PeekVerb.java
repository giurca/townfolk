package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.ContainerAdapters;
import com.yucareux.townfolk.town.StorageIndex;
import com.yucareux.townfolk.town.StorageRegistry;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.BlockTaskQueue;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: peek [hint]]} / {@code [ACTION: inspect [hint]]} —
 * open and immediately close a registered container to refresh the
 * storage ledger and surface contents through {@code ActionFeedback}.
 *
 * <p>If {@code hint} resolves to a labelled barrel via
 * {@link StorageRegistry#findByLabel}, that specific barrel is the
 * target — if the villager is out of reach, a walk is queued so they
 * peek the named barrel on arrival. Without a hint, falls back to
 * the nearest container within 6 blocks.
 *
 * <p>Extracted from {@code ToolDispatcher.doPeekNearest} in stage 17a.3.
 */
public final class PeekVerb {

   private PeekVerb() {}

   public static void run(VerbContext ctx) {
      String hint = ctx.body();
      StorageIndex.Hit hit = null;
      if (hint != null && !hint.isBlank()) {
         BlockPos labelPos = StorageRegistry.findByLabel(ctx.level(), hint, ctx.actor().blockPosition());
         if (labelPos != null) {
            if (labelPos.distSqr(ctx.actor().blockPosition()) <= 6 * 6) {
               var c = ContainerAdapters.at(ctx.level(), labelPos);
               if (c != null) {
                  hit = new StorageIndex.Hit(labelPos, c);
               }
            } else {
               // Out of reach — walk to the named barrel, then re-fire peek.
               final BlockPos lpFinal = labelPos;
               final var townRef = ctx.town();
               final var selfRef = ctx.self();
               final String verbKeyFinal = ctx.verbKey();
               final String actionRawFinal = ctx.actionRaw();
               BlockTaskQueue.enqueue(ctx.level(), ctx.actor(), new BlockTaskQueue.BlockTask(
                  ctx.actor().getUUID(), lpFinal,
                  ctx.level().getGameTime() + 20L * 60, "peek",
                  (lvl, v, p) -> {
                     VerbContext arrived = new VerbContext(lvl, townRef, v, selfRef,
                        verbKeyFinal, hint, actionRawFinal);
                     run(arrived);
                     return "peeked the named barrel at " + p.toShortString();
                  }));
               VerboseLog.write("STORAGE_NAVIGATE_BY_LABEL",
                  "actor=" + ctx.self().name() + " verb=peek pos=" + lpFinal.toShortString()
                     + " hint=\"" + hint + "\"", "");
               return;
            }
         }
      }
      if (hit == null) hit = StorageIndex.nearest(ctx.level(), ctx.actor().blockPosition(), 6);
      if (hit == null) {
         ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
            ctx.self().name() + " peers around but sees no container");
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "peek — no container within 6 blocks"
               + (hint != null && !hint.isBlank() ? " (no labelled \"" + hint + "\" either)" : ""));
         return;
      }
      Container container = hit.container();
      BlockPos cpos = hit.pos();
      StorageHelpers.playContainerSound(ctx.level(), cpos, true);
      StorageHelpers.playContainerSound(ctx.level(), cpos, false);
      StorageRegistry.touch(ctx.level(), cpos, ctx.self().name(), ctx.level().getGameTime());
      long day = ctx.level().getGameTime() / 24000L;
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO,
         ctx.self().name() + " inspected container at " + cpos.toShortString());
      MemoryStore.write(ctx.actor(), "storage", day,
         "I peeked into " + StorageHelpers.containerRefFor(ctx.level(), cpos) + ".");
      StringBuilder peekSummary = new StringBuilder("peeked container at ");
      peekSummary.append(cpos.toShortString()).append(": ");
      boolean any = false;
      LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack s = container.getItem(i);
         if (s.isEmpty()) continue;
         merged.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
      }
      for (var en : merged.entrySet()) {
         if (any) peekSummary.append(", ");
         peekSummary.append(en.getValue()).append("× ").append(en.getKey());
         any = true;
      }
      if (!any) peekSummary.append("empty");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(), peekSummary.toString());
   }
}

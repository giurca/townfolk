package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.AnimalPlanRegistry;
import com.yucareux.townfolk.town.CropPlanRegistry;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.BlockTaskQueue;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;

/**
 * {@code [ACTION: forget_parcel <id-or-label>]} (also {@code drop_parcel}
 * / {@code unbind_parcel}) — the LLM-driven counterpart to the
 * sneak-stake unbind path. Matches by exact parcel id first, then
 * by fuzzy substring against the parcel's {@code shortLabel} so the
 * LLM can write {@code forget_parcel west plot} or {@code forget_parcel 8x4}.
 *
 * <p>Cancels any in-flight block task tied to the dropped parcel and
 * drops the parcel's crop / animal plan from their respective
 * registries — otherwise per-parcel plan entries would leak every
 * time a villager rebuilds a plot.
 *
 * <p>Extracted from {@code ToolDispatcher.doForgetParcel} in stage 16b.2.b.
 */
public final class ForgetParcelVerb {

   private ForgetParcelVerb() {}

   public static void run(VerbContext ctx) {
      var comp = ctx.actor().getData(ModRegistries.LLM_VILLAGER.get());
      if (comp.parcels().isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "forget_parcel — I own no land to drop");
         return;
      }
      String body = ctx.body();
      String needle = body.toLowerCase(Locale.ROOT).trim();
      FieldRegion match = null;
      // 1: exact id.
      for (var p : comp.parcels()) {
         if (p.id().equals(body)) { match = p; break; }
      }
      // 2: fuzzy label (size or direction).
      if (match == null && !needle.isEmpty()) {
         for (var p : comp.parcels()) {
            String label = p.shortLabel(ctx.town().getBlockPos()).toLowerCase(Locale.ROOT);
            if (label.contains(needle)) { match = p; break; }
         }
      }
      // 3: empty body or no match — fail with a description of available parcels.
      if (match == null) {
         StringBuilder list = new StringBuilder();
         for (var p : comp.parcels()) {
            if (list.length() > 0) list.append(", ");
            list.append(p.shortLabel(ctx.town().getBlockPos())).append(" [id=").append(p.id()).append("]");
         }
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "forget_parcel — no parcel matched \"" + body + "\". Available: " + list);
         return;
      }
      ctx.actor().setData(ModRegistries.LLM_VILLAGER.get(), comp.withoutParcel(match.id()));
      BlockTaskQueue.cancel(ctx.actor().getUUID());
      CropPlanRegistry.forget(ctx.level(), match.id());
      AnimalPlanRegistry.forget(ctx.level(), match.id());
      long day = ctx.level().getGameTime() / 24000L;
      String label = match.shortLabel(ctx.town().getBlockPos());
      String msg = ctx.self().name() + " dropped their claim to " + label;
      VerboseLog.write("ACTION_RESULT",
         "actor=" + ctx.self().name() + " status=forget_parcel",
         "id=" + match.id() + " label=" + label);
      ctx.town().getTown().log().add(ctx.level().getGameTime(), TownLog.Level.INFO, msg);
      MemoryStore.write(ctx.actor(), "parcel", day,
         "I gave up my claim to the " + label + " plot.");
      ActionFeedback.recordOk(ctx.actor().getUUID(), ctx.level().getGameTime(),
         "dropped parcel " + label);
   }
}

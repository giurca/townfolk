package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.EntityTaskQueue;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import com.yucareux.townfolk.world.livestock.LivestockTask;
import com.yucareux.townfolk.world.livestock.LivestockTasks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.animal.Animal;

/**
 * {@code [ACTION: shear|milk|breed [species-hint]]} — generic
 * dispatch to the {@link LivestockTask} registry. Picks every task
 * matching the verb, filters by optional species hint, then picks
 * the (task, target) pair where the villager has the required tool
 * AND the animal is ready, nearest first.
 *
 * <p>Three verbs share this entry point because the LLM uses
 * distinct keywords but the underlying flow is identical — the
 * VerbDef table wires each verb to a {@link #runShear}/{@link #runMilk}/
 * {@link #runBreed} thin trampoline.
 *
 * <p>Extracted from {@code ToolDispatcher.doLivestock} in stage 17a.5.
 */
public final class LivestockVerb {

   private LivestockVerb() {}

   public static void runShear(VerbContext ctx) { runImpl(ctx, "shear"); }
   public static void runMilk (VerbContext ctx) { runImpl(ctx, "milk"); }
   public static void runBreed(VerbContext ctx) { runImpl(ctx, "breed"); }

   private static void runImpl(VerbContext ctx, String verb) {
      List<LivestockTask> candidates = new ArrayList<>();
      for (var t : LivestockTasks.ALL) {
         if (t.verb().equals(verb)) candidates.add(t);
      }
      if (candidates.isEmpty()) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            verb + " — no such livestock task registered");
         return;
      }

      String hintKey = ctx.body().toLowerCase(Locale.ROOT).trim();
      if (!hintKey.isEmpty()) {
         candidates.removeIf(t -> {
            String desc = t.targetType().getSimpleName().toLowerCase(Locale.ROOT);
            return !t.id().contains(hintKey) && !desc.contains(hintKey);
         });
         if (candidates.isEmpty()) {
            ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
               verb + " — no " + hintKey + " task registered");
            return;
         }
      }

      var box = ctx.actor().getBoundingBox().inflate(12);
      LivestockTask chosenTask = null;
      Animal chosenTarget = null;
      double bestDist = Double.MAX_VALUE;
      for (var task : candidates) {
         if (!task.hasTool(ctx.actor())) continue;
         for (var a : ctx.level().getEntitiesOfClass(task.targetType(), box,
               an -> task.ready(ctx.level(), an, ctx.actor()))) {
            double d = ctx.actor().distanceToSqr(a);
            if (d < bestDist) { bestDist = d; chosenTask = task; chosenTarget = a; }
         }
      }
      if (chosenTask == null || chosenTarget == null) {
         boolean anyToolOk = candidates.stream().anyMatch(t -> t.hasTool(ctx.actor()));
         if (!anyToolOk) {
            String toolList = candidates.stream()
               .map(t -> t.toolItemId() == null ? "the right tool" : t.toolItemId())
               .distinct().reduce((a, b) -> a + " or " + b).orElse("a tool");
            ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
               verb + " — I need " + toolList);
         } else {
            ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
               verb + " — no ready target within 12 blocks");
         }
         return;
      }

      final var taskF = chosenTask;
      final var targetF = chosenTarget;
      final var townF = ctx.town();
      EntityTaskQueue.enqueue(ctx.level(), ctx.actor(), new EntityTaskQueue.EntityTask(
         ctx.actor().getUUID(), targetF.getUUID(),
         ctx.level().getGameTime() + 20L * 30, verb,
         (lvl, v, t) -> {
            if (!(t instanceof Animal a)) {
               throw new RuntimeException(verb + " — target is no longer an animal");
            }
            return taskF.perform(lvl, v, a, /* parcel */ null, townF);
         }));
   }
}

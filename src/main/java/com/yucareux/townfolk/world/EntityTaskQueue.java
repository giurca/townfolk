package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.NavCall;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Entity-targeted parallel to {@link BlockTaskQueue}. Same shape, same
 * lifecycle, same completion-listener mechanism — just the target is a
 * UUID-referenced {@link LivingEntity} instead of a {@link BlockPos}.
 * The completion listener is what lets the work routine chain shears
 * (and future feed / milk / breed actions) without waiting for the
 * 15 s polling tick: on each successful arrival, listeners fire and
 * {@link WorkProductionService} re-runs the routine immediately.
 *
 * Used by verbs whose subject is an animal or another villager (shear,
 * feed, lead, future "talk to <name>" deep dives). Walking to a moving
 * target is the obvious difference: we re-issue {@code moveTo} every
 * check tick because the entity may have drifted since the last path
 * was computed.
 *
 * Kept as a separate class instead of generalising BlockTaskQueue — the
 * arrival semantics (entity vs block) are different enough that fusing
 * them would muddy both. If we ever add 3+ target kinds we can lift a
 * common interface; today the duplication is tiny and clearer.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class EntityTaskQueue {

   private static final int CHECK_INTERVAL_TICKS = 5;       // 0.25 s
   private static final double ARRIVAL_DISTANCE_SQ = 6.25;  // 2.5-block reach (animals are skittish)
   private static final double WALK_SPEED = 0.6;

   @FunctionalInterface
   public interface OnArrive {
      String run(ServerLevel level, Villager actor, LivingEntity target);
   }

   public record EntityTask(
      UUID actor,
      UUID target,
      long deadlineGameTime,
      String verb,
      OnArrive onArrive
   ) {}

   /** Fires after a task's onArrive completes successfully. Used by
    *  {@link WorkProductionService} to chain-fire the parcel routine
    *  so a sheep-shearing villager doesn't wait 15 s between sheep. */
   public interface CompletionListener {
      void onCompleted(ServerLevel level, Villager actor, EntityTask task, LivingEntity target);
   }
   private static final java.util.List<CompletionListener> COMPLETION_LISTENERS =
      new java.util.concurrent.CopyOnWriteArrayList<>();
   public static void addCompletionListener(CompletionListener l) { COMPLETION_LISTENERS.add(l); }

   /** Deferred-fire record — same pattern as BlockTaskQueue. We collect
    *  completions inside the synchronized block, then fire listeners
    *  after release so a listener-enqueued follow-up doesn't trip the
    *  fail-fast iterator. */
   private record Completion(ServerLevel level, Villager actor, EntityTask task, LivingEntity target) {}

   /** ConcurrentHashMap so completion listeners can safely enqueue
    *  follow-up tasks mid-iteration. Pre-fix this was a plain HashMap
    *  guarded by a class-level monitor; the deferred-fire pattern was
    *  the workaround. */
   private static final Map<UUID, EntityTask> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

   public static void enqueue(ServerLevel level, Villager actor, EntityTask task) {
      PENDING.put(actor.getUUID(), task);
      var ent = level.getEntity(task.target);
      if (ent != null) NavCall.moveTo(actor, ent.blockPosition(), WALK_SPEED, "EntityTask:" + task.verb);
      String targetName = ent instanceof LivingEntity le && le.hasCustomName()
         ? le.getCustomName().getString()
         : (ent == null ? task.target.toString().substring(0, 8) : ent.getType().getDescription().getString());
      ActionFeedback.recordOk(actor.getUUID(), level.getGameTime(),
         "walking to " + targetName + " to " + task.verb);
      VerboseLog.write("ENTITY_TASK_QUEUE",
         "actor=" + nameOf(actor) + " verb=" + task.verb + " target=" + targetName, "");
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0L) return;

      java.util.List<Completion> completions = new java.util.ArrayList<>();

      // ConcurrentHashMap — weakly consistent iterator tolerates
      // listener-driven puts. Deferred-fire pattern retained so
      // listener bodies don't run inside the loop.
      {
         Iterator<Map.Entry<UUID, EntityTask>> it = PENDING.entrySet().iterator();
         while (it.hasNext()) {
            var e = it.next();
            EntityTask task = e.getValue();
            if (!(level.getEntity(task.actor) instanceof Villager v)) {
               // Wrong level — skip, don't remove. (Same cross-level bug we
               // hit in BlockTaskQueue: PENDING is global.)
               continue;
            }
            var targetEnt = level.getEntity(task.target);
            if (!(targetEnt instanceof LivingEntity target) || !target.isAlive()) {
               // Animal died or despawned mid-walk.
               ActionFeedback.recordFail(task.actor, level.getGameTime(),
                  task.verb + " — target is gone");
               it.remove();
               continue;
            }
            if (level.getGameTime() > task.deadlineGameTime) {
               ActionFeedback.recordFail(task.actor, level.getGameTime(),
                  task.verb + " — timed out (couldn't catch up)");
               it.remove();
               continue;
            }
            double d = v.distanceToSqr(target);
            if (d <= ARRIVAL_DISTANCE_SQ) {
               v.getNavigation().stop();
               try {
                  String result = task.onArrive.run(level, v, target);
                  if (result == null || result.isEmpty()) result = task.verb + " completed";
                  ActionFeedback.recordOk(task.actor, level.getGameTime(), result);
                  VerboseLog.write("ENTITY_TASK_DONE",
                     "actor=" + nameOf(v) + " verb=" + task.verb, result);
               } catch (Throwable t) {
                  ActionFeedback.recordFail(task.actor, level.getGameTime(),
                     task.verb + " — " + t.getMessage());
                  VerboseLog.write("ENTITY_TASK_ERROR",
                     "actor=" + nameOf(v) + " verb=" + task.verb, "error=" + t);
               }
               ScheduleService.suspendFor(level, task.actor, 20L * 30);
               it.remove();
               completions.add(new Completion(level, v, task, target));
            } else {
               // Refresh path — target may have moved.
               BlockPos cur = target.blockPosition();
               if (!v.getNavigation().isInProgress()
                   || v.getNavigation().getTargetPos() == null
                   || v.getNavigation().getTargetPos().distSqr(cur) > 4.0) {
                  NavCall.moveTo(v, cur, WALK_SPEED, "EntityTask:" + task.verb + " (chase)");
               }
            }
         }
      }

      // Listeners fire OUTSIDE the synchronized block — same defensive
      // pattern as BlockTaskQueue, so a listener that re-enqueues
      // (e.g. ParcelRoutine.tryTick → next shear) doesn't trip our
      // iterator.
      for (Completion c : completions) {
         for (CompletionListener l : COMPLETION_LISTENERS) {
            try { l.onCompleted(c.level, c.actor, c.task, c.target); }
            catch (Throwable t) {
               VerboseLog.write("ENTITY_TASK_LISTENER_ERROR",
                  "actor=" + nameOf(c.actor) + " verb=" + c.task.verb, "error=" + t);
            }
         }
      }
   }

   private static String nameOf(Villager v) {
      return v.hasCustomName() ? v.getCustomName().getString()
                               : v.getUUID().toString().substring(0, 8);
   }

   private EntityTaskQueue() {}
}

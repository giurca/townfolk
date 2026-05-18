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
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Single shared "go to a block, do a thing there" runtime, used by every
 * block-interaction action the LLM can emit (harvest, plant, chop, mine,
 * place, …). New verbs only need to construct a {@link BlockTask} and call
 * {@link #enqueue}; they do NOT reinvent pathfinding / arrival detection /
 * timeout handling.
 *
 *   verb (LLM)  →  resolve target block  →  enqueue BlockTask
 *                                            ↓
 *                  BlockTaskQueue ticks every {@link #CHECK_INTERVAL_TICKS}
 *                  ┌── villager close enough?  → onArrive.run(level, villager)
 *                  └── deadline passed?        → record fail feedback
 *
 * Only one task per villager at a time. If the LLM enqueues a second while
 * the first is still pending, the new one replaces it (most-recent-wins).
 * Transient state — not persisted; tasks simply vanish on shutdown / unload.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class BlockTaskQueue {

   private static final int CHECK_INTERVAL_TICKS = 5;       // 0.25 s — snappier arrival detection
   private static final double ARRIVAL_DISTANCE_SQ = 9.0;   // 3-block reach
   /** Vanilla villager working pace. Matches ScheduleService's WORK_SPEED so
    *  block-task walks look the same as schedule-driven walks. */
   private static final double WALK_SPEED = 0.6;

   /** What we'll do when the villager arrives at the target. */
   @FunctionalInterface
   public interface OnArrive {
      /** Return a short human-readable success message for the feedback log.
       *  Throwing or returning null signals failure; the caller writes its own
       *  fail message before-hand if it wants a richer reason. */
      String run(ServerLevel level, Villager actor, BlockPos pos);
   }

   public record BlockTask(
      UUID actor,
      BlockPos target,
      long deadlineGameTime,
      String verb,            // "harvest", "chop", ... — used in feedback strings
      OnArrive onArrive
   ) {}

   /** Per-villager pending task. Most-recent-wins on overlap.
    *
    *  {@link java.util.concurrent.ConcurrentHashMap} so the cross-thread
    *  read paths ({@link #hasPending}) and the tick-time mutators
    *  ({@link #enqueue}, {@link #cancel}) don't need a single global
    *  monitor. The iterator returned by entrySet() is weakly consistent
    *  and tolerates concurrent puts mid-iteration (which used to corrupt
    *  the old HashMap when a completion listener enqueued a follow-up
    *  task — the {@link Completion} deferred-fire pattern was the
    *  workaround). */
   private static final Map<UUID, BlockTask> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

   /** External "task completed successfully" listeners. Used by
    *  {@link WorkProductionService} to re-fire its routines the instant a
    *  step lands, collapsing the harvest→plant→harvest gap from "next
    *  15-second polling tick" to zero. The callback runs on the server tick
    *  thread, immediately after the task's onArrive and feedback writes.
    *  Listeners must be cheap — they should enqueue follow-up work, not
    *  perform long-running ops inline. */
   public interface CompletionListener {
      void onCompleted(ServerLevel level, Villager actor, BlockTask task);
   }
   private static final java.util.List<CompletionListener> COMPLETION_LISTENERS =
      new java.util.concurrent.CopyOnWriteArrayList<>();

   public static void addCompletionListener(CompletionListener l) { COMPLETION_LISTENERS.add(l); }

   /** Submit a task. If the villager already has a pending one it is replaced.
    *  Caller has already validated the target block and is sure the villager
    *  is the right actor (i.e. not stuck, not following a player, etc.). */
   public static void enqueue(ServerLevel level, Villager actor, BlockTask task) {
      PENDING.put(actor.getUUID(), task);
      // Kick off the walk immediately so the next prompt's RECENT ACTION block
      // reflects motion. Speed mirrors other nav callers.
      NavCall.moveTo(actor, task.target, WALK_SPEED, "BlockTask:" + task.verb);
      ActionFeedback.recordOk(actor.getUUID(), level.getGameTime(),
         "walking to " + task.target.toShortString() + " to " + task.verb);
      VerboseLog.write("BLOCK_TASK_QUEUE",
         "actor=" + nameOf(actor) + " verb=" + task.verb + " pos=" + task.target.toShortString(), "");
   }

   /** Drop any pending task for this villager (used by ScheduleService /
    *  FollowService when they want to take control). */
   public static void cancel(UUID actor) { PENDING.remove(actor); }

   public static boolean hasPending(UUID actor) { return PENDING.containsKey(actor); }

   /** Internal struct used to defer listener fires until AFTER we release the
    *  PENDING-iteration lock. Otherwise a listener that enqueues a follow-up
    *  task (the harvest→plant chain) puts a new key into the HashMap we're
    *  iterating, which makes the fail-fast iterator silently bail on the very
    *  next .hasNext() — leaving subsequent arrivals undetected for an entire
    *  tick cycle. */
   private record Completion(ServerLevel level, Villager actor, BlockTask task) {}

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % CHECK_INTERVAL_TICKS != 0L) return;

      java.util.List<Completion> completions = new java.util.ArrayList<>();

      // ConcurrentHashMap's iterator is weakly consistent and supports
      // concurrent puts mid-iteration — completion listeners can safely
      // enqueue follow-up tasks while we walk. We STILL defer listener
      // invocation until after the iteration so the listener bodies
      // don't run inside our hot loop.
      {
         Iterator<Map.Entry<UUID, BlockTask>> it = PENDING.entrySet().iterator();
         while (it.hasNext()) {
            var entry = it.next();
            BlockTask task = entry.getValue();
            if (!(level.getEntity(task.actor) instanceof Villager v)) {
               // Actor isn't in THIS level. PENDING is global — every loaded
               // ServerLevel (Overworld, Nether, End, …) fires the LevelTick
               // event and walks this same map. If we removed here, the
               // Nether tick would clear Overworld villagers' tasks and the
               // re-queue/garbage-collect cycle would burn 15 seconds before
               // the right level got a look in. Skip instead — the entity
               // is still real, just not in this level's snapshot.
               continue;
            }
            // Deadline?
            if (level.getGameTime() > task.deadlineGameTime) {
               ActionFeedback.recordFail(task.actor, level.getGameTime(),
                  task.verb + " at " + task.target.toShortString() + " — timed out (couldn't reach)");
               VerboseLog.write("BLOCK_TASK_TIMEOUT",
                  "actor=" + nameOf(v) + " verb=" + task.verb + " pos=" + task.target.toShortString(), "");
               it.remove();
               continue;
            }
            // Reached?
            double d = v.distanceToSqr(
               task.target.getX() + 0.5, task.target.getY() + 0.5, task.target.getZ() + 0.5);
            if (d <= ARRIVAL_DISTANCE_SQ) {
               v.getNavigation().stop();
               try {
                  String result = task.onArrive.run(level, v, task.target);
                  if (result == null || result.isEmpty()) result = task.verb + " completed";
                  ActionFeedback.recordOk(task.actor, level.getGameTime(), result);
                  VerboseLog.write("BLOCK_TASK_DONE",
                     "actor=" + nameOf(v) + " verb=" + task.verb + " pos=" + task.target.toShortString(),
                     result);
               } catch (Throwable t) {
                  ActionFeedback.recordFail(task.actor, level.getGameTime(),
                     task.verb + " at " + task.target.toShortString() + " — " + t.getMessage());
                  VerboseLog.write("BLOCK_TASK_ERROR",
                     "actor=" + nameOf(v) + " verb=" + task.verb + " pos=" + task.target.toShortString(),
                     "error=" + t);
               }
               ScheduleService.suspendFor(level, task.actor, 20L * 30);
               it.remove();
               // Defer listener fire — see Completion record above for why.
               completions.add(new Completion(level, v, task));
            } else if (!v.getNavigation().isInProgress()) {
               NavCall.moveTo(v, task.target, WALK_SPEED, "BlockTask:" + task.verb + " (retry)");
            }
         }
      }

      // Listeners fire OUTSIDE the iteration loop. With ConcurrentHashMap we
      // could technically fire inline, but deferring keeps the hot loop tight
      // and avoids surprising re-entrancy through listener bodies.
      for (Completion c : completions) {
         for (CompletionListener l : COMPLETION_LISTENERS) {
            try { l.onCompleted(c.level, c.actor, c.task); }
            catch (Throwable t) {
               VerboseLog.write("BLOCK_TASK_LISTENER_ERROR",
                  "actor=" + nameOf(c.actor) + " verb=" + c.task.verb, "error=" + t);
            }
         }
      }
   }

   private static String nameOf(Villager v) {
      return v.hasCustomName() ? v.getCustomName().getString()
                               : v.getUUID().toString().substring(0, 8);
   }

   private BlockTaskQueue() {}
}

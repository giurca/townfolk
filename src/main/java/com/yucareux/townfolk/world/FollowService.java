package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Stage 4 helper: continuous follow. A villager can be set to track an
 * entity (villager or player) until the expiry tick or until the target
 * moves to a different dimension / disappears.
 *
 *   refresh cadence: every 20 ticks (1 s) the nav target is re-pinned to
 *                    the follower's current position
 *   default duration: 4 real minutes (4800 ticks)
 *   max distance:    if the gap exceeds ~32 blocks for 3 consecutive ticks
 *                    the follow auto-cancels (lost in the woods)
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class FollowService {

   private static final int REFRESH_TICKS = 10;   // 0.5 s — snappier follow
   public static final int DEFAULT_DURATION_TICKS = 4800;
   private static final double GIVE_UP_DISTANCE_SQ = 64.0 * 64.0;
   private static final double FOLLOW_SPEED = 0.65;
   private static final long START_DEBOUNCE_TICKS = 200;   // 10 s between start() calls per actor

   private record State(UUID targetUuid, long expireGameTime) {}

   private static final Map<UUID, State> ACTIVE = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> LAST_START_TICK = new ConcurrentHashMap<>();

   public record FollowSnapshot(UUID targetUuid, long expireGameTime) {}

   public static java.util.Optional<FollowSnapshot> peek(UUID actor) {
      State s = ACTIVE.get(actor);
      return s == null ? java.util.Optional.empty()
         : java.util.Optional.of(new FollowSnapshot(s.targetUuid(), s.expireGameTime()));
   }

   public static void start(ServerLevel level, UUID actor, UUID target, long expireAt) {
      long now = level.getGameTime();
      Long last = LAST_START_TICK.get(actor);
      if (last != null && now - last < START_DEBOUNCE_TICKS) {
         // Debounce: refresh the existing follow's expiry rather than emitting a new "started"
         // event. Avoids spam when the LLM repeats [ACTION: follow X] every turn.
         //
         // Re-target inside the debounce window: if the new target
         // DIFFERS from the existing one, we MUST honour the new
         // command — otherwise "follow Alice → follow Bob" within
         // 200 ms silently keeps Alice followed (audit item 1.6).
         // Fall through to the full start path to switch targets.
         State existing = ACTIVE.get(actor);
         if (existing != null && existing.targetUuid().equals(target)) {
            ACTIVE.put(actor, new State(target, expireAt));
            VerboseLog.write("FOLLOW_START_DEBOUNCED",
               "actor=" + actor + " target=" + target + " sinceLast=" + (now - last) + "t", "");
            return;
         }
         VerboseLog.write("FOLLOW_RETARGET_FAST",
            "actor=" + actor
               + " from=" + (existing == null ? "none" : existing.targetUuid())
               + " to=" + target
               + " sinceLast=" + (now - last) + "t",
            "debounce window overridden — new target wins");
         // Fall through to the full start logic below.
      }
      LAST_START_TICK.put(actor, now);
      ACTIVE.put(actor, new State(target, expireAt));
      // Cancel any pending one-shot intent — they'd compete with our follow
      // for the navigator lock every tick.
      VanillaOverrides.clearIntent(actor);
      VerboseLog.write("FOLLOW_START",
         "actor=" + actor + " target=" + target + " expireAt=" + expireAt, "");
      Entity actorEnt = level.getEntity(actor);
      Entity targetEnt = level.getEntity(target);
      // Immediately reset nav and fire the first moveTo. Don't wait up to a
      // second for the next onTick — that delay caused "frozen after assign"
      // reports because LivingEntity.noActionTime can climb in the gap and
      // gate serverAiStep, which is what ticks the navigator.
      if (actorEnt instanceof Villager v && targetEnt != null) {
         com.yucareux.townfolk.diag.NavCall.stop(v, "FollowService.start[pre]");
         var pos = targetEnt.blockPosition();
         com.yucareux.townfolk.diag.NavCall.moveTo(v, pos, FOLLOW_SPEED, "FollowService.start[initial]");
      }
      if (actorEnt instanceof Villager v && targetEnt != null) {
         String tname = targetEnt.hasCustomName() ? targetEnt.getCustomName().getString()
            : targetEnt.getName().getString();
         long day = level.getGameTime() / 24000L;
         com.yucareux.townfolk.villager.MemoryStore.write(v, "action", day, "I started following " + tname);
      }
   }

   public static void stop(ServerLevel level, UUID actor) {
      State removed = ACTIVE.remove(actor);
      LAST_START_TICK.remove(actor);
      if (removed == null) return;
      VerboseLog.write("FOLLOW_STOP", "actor=" + actor + " target=" + removed.targetUuid(), "");
      Entity actorEnt = level.getEntity(actor);
      Entity targetEnt = level.getEntity(removed.targetUuid());
      if (actorEnt instanceof Villager v) {
         String tname = targetEnt == null ? "them"
            : (targetEnt.hasCustomName() ? targetEnt.getCustomName().getString() : targetEnt.getName().getString());
         long day = level.getGameTime() / 24000L;
         com.yucareux.townfolk.villager.MemoryStore.write(v, "action", day, "I stopped following " + tname);
      }
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % REFRESH_TICKS != 0L) return;
      if (ACTIVE.isEmpty()) return;

      long now = level.getGameTime();
      Iterator<Map.Entry<UUID, State>> it = ACTIVE.entrySet().iterator();
      while (it.hasNext()) {
         var e = it.next();
         State st = e.getValue();
         if (now > st.expireGameTime()) {
            endAndEmit(level, e.getKey(), st.targetUuid(), "I got tired of following and stopped", "expired");
            it.remove();
            continue;
         }
         // LevelTickEvent.Post fires per loaded ServerLevel. The actor lives
         // in exactly one level — for the others, getEntity returns null and
         // that is NOT a real "missing" condition. Skip silently and let the
         // tick for the correct level process this entry.
         Entity actor = level.getEntity(e.getKey());
         if (actor == null) continue;
         if (!(actor instanceof Villager villager)) {
            endAndEmit(level, e.getKey(), st.targetUuid(), "I lost sight of the person I was following", "entity_missing_actor_wrong_type");
            it.remove();
            continue;
         }
         Entity target = level.getEntity(st.targetUuid());
         if (target == null) {
            endAndEmit(level, e.getKey(), st.targetUuid(), "I lost sight of the person I was following", "entity_missing_target");
            it.remove();
            continue;
         }
         if (actor.level() != target.level()) {
            endAndEmit(level, e.getKey(), st.targetUuid(), "They went somewhere I can't follow", "cross_dimension");
            it.remove();
            continue;
         }
         if (actor.distanceToSqr(target) > GIVE_UP_DISTANCE_SQ) {
            endAndEmit(level, e.getKey(), st.targetUuid(), "They got too far ahead — I gave up following", "too_far");
            it.remove();
            continue;
         }
         var pos = target.blockPosition();
         com.yucareux.townfolk.diag.NavCall.moveTo(villager, pos, FOLLOW_SPEED, "FollowService.refresh");
      }
   }

   private static void endAndEmit(ServerLevel level, UUID actorUuid, UUID targetUuid,
                                  String narrative, String reason) {
      LAST_START_TICK.remove(actorUuid);  // allow immediate re-follow attempts
      VerboseLog.write("FOLLOW_END", "actor=" + actorUuid + " reason=" + reason, "");
      Entity actor = level.getEntity(actorUuid);
      if (!(actor instanceof Villager v)) return;
      var comp = v.getData(com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      if (comp.townSquarePos() == 0L) return;
      long day = level.getGameTime() / 24000L;
      com.yucareux.townfolk.villager.MemoryStore.write(v, "action", day, narrative);
      net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(comp.townSquarePos());
      if (level.getBlockEntity(pos) instanceof com.yucareux.townfolk.blockentity.TownSquareBlockEntity town) {
         String who = v.hasCustomName() ? v.getCustomName().getString() : "?";
         town.getTown().log().add(level.getGameTime(),
            com.yucareux.townfolk.town.TownLog.Level.INFO,
            who + ": " + narrative);
      }
   }

   private FollowService() {}
}

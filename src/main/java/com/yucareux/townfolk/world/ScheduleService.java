package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.NavCall;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives villagers' daily rhythm based on the in-game clock and their
 * player-set anchors. Zero LLM cost — pure mechanics — but the resulting
 * motion seeds the RAG store with naturally-occurring memories
 * ("I woke up", "I arrived at the forge", "I went to bed") via the
 * existing world-event + brain-event listeners.
 *
 * Phases (using level.getDayTime() % 24000, where 0=sunrise):
 *   0     – 1000   : dawn   — wake up
 *   1000  – 9000   : work   — go to workstation (if assigned)
 *   9000  – 12000  : evening — drift toward home
 *   12000 – 13000  : dusk   — be at home
 *   13000 – 24000  : night  — sleep in bed
 *
 * Priority hierarchy (schedule yields to everything explicit):
 *   1. Active player-issued follow  (FollowService.ACTIVE)
 *   2. Active in-progress navigation (already pathing somewhere)
 *   3. Schedule (this service) — only fires if 1 and 2 are absent
 *
 * Per-villager activity (waking / going_to_work / at_work / going_home /
 * sleeping / idle) is exposed via {@link #activityOf(UUID)} so
 * {@link WorldSense} can drop it into the LLM prompt.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class ScheduleService {

   private static final int TICKS_PER_CHECK = 60;        // 3 s
   private static final double WORK_SPEED = 0.5;
   private static final double HOME_SPEED = 0.5;
   private static final double SLEEP_RADIUS_SQ = 4.0;     // close enough to enter bed

   /** Current schedule activity, keyed by villager UUID. Transient. */
   private static final Map<UUID, String> ACTIVITY = new ConcurrentHashMap<>();

   /** Suspend-until tick by villager. While suspended, schedule yields to whatever
    *  the villager (or the player via IntentExecutor) is doing. Transient. */
   private static final Map<UUID, Long> SUSPEND_UNTIL = new ConcurrentHashMap<>();

   /** Last day-phase we applied to each villager. Lets us detect phase
    *  boundaries (work_hours → evening, evening → night, etc.) and force a
    *  re-evaluation even when the villager is suspended — otherwise a
    *  long-running block-task chain keeps renewing the suspend and the
    *  schedule never gets to send them home or to bed. */
   private static final Map<UUID, String> LAST_PHASE = new ConcurrentHashMap<>();

   /** Default override duration when a player issues an explicit movement
    *  command — long enough for the villager to actually arrive AND get a
    *  little settled time before the schedule resumes. */
   /** How long the schedule yields after a player-initiated intent
    *  (dialogue "go home" / "follow me" / etc.). 90 seconds gives the
    *  villager time to actually begin following the order before the
    *  scheduled activity reclaims them, but short enough that a
    *  casual chat near bedtime doesn't lock the villager out of
    *  sleep for the rest of the night. Phase boundaries also clear
    *  the suspend (see {@link #onTick}). */
   public static final long PLAYER_OVERRIDE_TICKS = 20L * 90;       // 90 seconds

   public static String activityOf(UUID actor) {
      return ACTIVITY.getOrDefault(actor, "idle");
   }

   /** Pause schedule decisions for {@code actor} for {@code durationTicks} of
    *  game time. Called by IntentExecutor when the player explicitly redirects
    *  the villager so the schedule doesn't immediately fight them back. */
   public static void suspendFor(net.minecraft.server.level.ServerLevel level, UUID actor, long durationTicks) {
      SUSPEND_UNTIL.put(actor, level.getGameTime() + durationTicks);
   }

   public static boolean isSuspended(net.minecraft.server.level.ServerLevel level, UUID actor) {
      Long until = SUSPEND_UNTIL.get(actor);
      if (until == null) return false;
      if (level.getGameTime() >= until) {
         SUSPEND_UNTIL.remove(actor);
         return false;
      }
      return true;
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % TICKS_PER_CHECK != 0L) return;

      long dayTime = level.getDayTime() % 24000L;
      String phase = phaseOf(dayTime);

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.townSquarePos() == 0L) continue;

            // Yield to active player commands.
            if (FollowService.peek(v.getUUID()).isPresent()) {
               ACTIVITY.put(v.getUUID(), "following");
               LAST_PHASE.put(v.getUUID(), phase);
               continue;
            }

            // Phase-boundary override: when the day phase changes (work →
            // evening → dusk → night → dawn), the schedule MUST get a
            // turn — otherwise a long-running block-task chain that keeps
            // renewing the suspend would prevent the villager from ever
            // going home or to bed. Crossing a boundary clears the suspend
            // AND cancels any pending block task so the new phase's
            // navigation can take over.
            String prevPhase = LAST_PHASE.get(v.getUUID());
            boolean phaseChanged = prevPhase != null && !prevPhase.equals(phase);
            if (phaseChanged) {
               SUSPEND_UNTIL.remove(v.getUUID());
               BlockTaskQueue.cancel(v.getUUID());
               com.yucareux.townfolk.diag.VerboseLog.write("SCHED_PHASE",
                  "villager=" + nameOf(v) + " was=" + prevPhase + " now=" + phase,
                  "playerSetHome=" + comp.playerSetHome()
                  + " hasHomeMem=" + v.getBrain().hasMemoryValue(MemoryModuleType.HOME)
                  + " sleeping=" + v.isSleeping());
               // Phase transition also fires any "phase:<phase>" reflexes
               // attached to this villager.
               ReflexService.onPhaseEnter(level, v, phase);
            } else if (isSuspended(level, v.getUUID())) {
               // Intra-phase suspend (block-task grace window or player
               // intent override) — let it run without interference.
               Long until = SUSPEND_UNTIL.get(v.getUUID());
               long left = until == null ? 0 : (until - level.getGameTime());
               com.yucareux.townfolk.diag.VerboseLog.write("SCHED_SUSPENDED",
                  "villager=" + nameOf(v) + " phase=" + phase + " ticksLeft=" + left,
                  "schedule skipped — block-task grace window blocks bedtime nav!");
               ACTIVITY.putIfAbsent(v.getUUID(), "idle");
               continue;
            }

            tickVillager(level, v, comp, phase);
            LAST_PHASE.put(v.getUUID(), phase);
         }
      }
   }

   private static void tickVillager(ServerLevel level, Villager v, LlmVillagerComponent comp, String phase) {
      String desired = desiredActivity(comp, phase);
      String current = ACTIVITY.get(v.getUUID());
      String name = nameOf(v);

      // Sleeping handler is special — we may need to wake them at dawn.
      if (v.isSleeping() && !desired.equals("sleeping")) {
         v.stopSleeping();
         VerboseLog.write("SCHED_WAKE",
            "villager=" + name + " phase=" + phase, "");
      }

      // Day rollover: clear any stale leisure plan so the next evening
      // rolls fresh. Dawn is the natural reset point.
      if ("dawn".equals(phase)) {
         com.yucareux.townfolk.world.leisure.LeisureService.clearChoice(v.getUUID());
      }

      // Yield to LeisureService during the evening band when a plan
      // is in flight. The leisure layer owns the villager's movement
      // (walking to the tavern, ambling around, etc.); we'd otherwise
      // fight it on every tick by re-firing going_home. Dusk and
      // later phases reclaim control so the villager makes bed in
      // time. setActivity is harmless to skip — the leisure executors
      // don't read it, and the activity label resumes once dusk hits.
      if ("evening".equals(phase)
          && com.yucareux.townfolk.world.leisure.LeisureService.hasActivePlan(v.getUUID())) {
         var choice = com.yucareux.townfolk.world.leisure.LeisureService.currentChoice(v.getUUID());
         if (choice != null
             && choice.activity() != com.yucareux.townfolk.world.leisure.LeisureActivity.STAY_HOME) {
            // Reflect the leisure activity in the status string so the
            // admin UI and pulse strip read it ("at_tavern", "wandering").
            String label = switch (choice.activity()) {
               case TAVERN -> "at_tavern";
               case WALK   -> "wandering";
               default      -> "idle";
            };
            setActivity(v, label);
            return;
         }
         // STAY_HOME falls through to the existing going_home / at_home path.
      }

      switch (desired) {
         case "going_to_work" -> {
            GlobalPos job = comp.playerSetJob()
               ? v.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null) : null;
            if (job == null) {
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=" + desired,
                  "no JOB_SITE memory (playerSetJob=" + comp.playerSetJob() + ") → idle");
               setActivity(v, "idle"); return;
            }
            double d = v.distanceToSqr(job.pos().getX() + 0.5, job.pos().getY(), job.pos().getZ() + 0.5);
            if (d < 4.0) {
               setActivity(v, "at_work");
            } else if (!v.getNavigation().isInProgress()) {
               NavCall.moveTo(v, job.pos(), WORK_SPEED, "ScheduleService.work");
               setActivity(v, "going_to_work");
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=going_to_work", "moveTo=" + job.pos().toShortString() + " distSq=" + d);
            }
         }
         case "at_work" -> {
            if (!"at_work".equals(current)) {
               setActivity(v, "at_work");
               long day = level.getGameTime() / 24000L;
               MemoryStore.write(v, "schedule", day, "I settled in at my workstation for the day's work.");
            }
         }
         case "going_home" -> {
            GlobalPos home = comp.playerSetHome()
               ? v.getBrain().getMemory(MemoryModuleType.HOME).orElse(null) : null;
            if (home == null) {
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=going_home",
                  "no HOME memory (playerSetHome=" + comp.playerSetHome() + ") → idle");
               setActivity(v, "idle"); return;
            }
            double d = v.distanceToSqr(home.pos().getX() + 0.5, home.pos().getY(), home.pos().getZ() + 0.5);
            if (d < 9.0) {
               setActivity(v, "at_home");
            } else if (!v.getNavigation().isInProgress()) {
               NavCall.moveTo(v, home.pos(), HOME_SPEED, "ScheduleService.home");
               setActivity(v, "going_home");
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=going_home", "moveTo=" + home.pos().toShortString() + " distSq=" + d);
            }
         }
         case "at_home" -> {
            if (!"at_home".equals(current)) {
               setActivity(v, "at_home");
            }
         }
         case "sleeping" -> {
            GlobalPos home = comp.playerSetHome()
               ? v.getBrain().getMemory(MemoryModuleType.HOME).orElse(null) : null;
            if (home == null) {
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=sleeping",
                  "no HOME memory (playerSetHome=" + comp.playerSetHome() + ") → idle. "
                  + "Player must press X aiming at a bed to assign one.");
               setActivity(v, "idle"); return;
            }
            if (v.isSleeping()) {
               setActivity(v, "sleeping");
               return;
            }
            BlockPos pos = home.pos();
            var bedState = level.getBlockState(pos);
            boolean isBed = bedState.getBlock() instanceof net.minecraft.world.level.block.BedBlock;

            // GUARD 1: refuse to sleep if the HOME block is no longer a
            // bed. Otherwise startSleeping puts the villager into the
            // sleeping pose AT pos (which may now be air, dirt, etc.)
            // — they'll appear lying flat outdoors. Prefer staying idle
            // and writing a need-bed note so the player knows to
            // reassign.
            if (!isBed) {
               // Bed was broken / replaced — scan nearby for any free
               // bed and reassign on the fly. Without this fallback,
               // the villager just stands outside the door every
               // night until the player manually re-runs claim_home
               // (audit item L).
               BlockPos fallback = findNearbyFreeBed(level, pos, 16);
               String blockKind = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                  .getKey(bedState.getBlock()).getPath();
               if (fallback != null) {
                  // Reassign HOME to the new bed and continue with
                  // sleep on the next pass.
                  v.getBrain().setMemory(
                     net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,
                     net.minecraft.core.GlobalPos.of(level.dimension(), fallback));
                  VerboseLog.write("SCHED_SLEEP_REASSIGN", "villager=" + name
                     + " oldPos=" + pos.toShortString() + " oldBlock=" + blockKind
                     + " newPos=" + fallback.toShortString(),
                     "HOME bed was broken — auto-claimed a nearby free bed");
                  setActivity(v, "going_home");
                  NavCall.moveTo(v, fallback, HOME_SPEED, "ScheduleService.sleep.reassign");
                  return;
               }
               // No bed available — write a need-bed memory so dialogue
               // can hail the player, and stay idle for tonight.
               setActivity(v, "idle");
               long day = level.getGameTime() / 24000L;
               com.yucareux.townfolk.villager.MemoryStore.write(v, "need:bed", day,
                  "My bed at " + pos.toShortString() + " is gone (now " + blockKind
                     + ") and there's no spare bed within 16 blocks. I need the player to assign one.");
               VerboseLog.write("SCHED_SLEEP_BLOCKED", "villager=" + name
                  + " pos=" + pos.toShortString() + " block=" + blockKind,
                  "HOME block is not a bed and no nearby fallback — wrote [need:bed] memory");
               return;
            }

            double d = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            // GUARD 2: also require villager to be on the same Y level
            // as the bed (within 1 block). Without this, a villager
            // standing on a roof directly above the bed with d < radius
            // could trigger sleep from the wrong floor. dy² is already
            // in d via distanceToSqr, but make the constraint explicit
            // for diagnostic clarity.
            double dy = Math.abs(v.getY() - pos.getY());

            if (d < SLEEP_RADIUS_SQ && dy <= 1.5) {
               try {
                  // Snap the villager's logical position exactly onto
                  // the bed before calling startSleeping. Otherwise the
                  // sleeping pose renders at their pre-sleep location
                  // (could be the doorway, an adjacent tile, etc.) and
                  // they look like they fell asleep outside the bed.
                  // Vanilla Villager.aiStep skips when sleeping, so
                  // this is the one safe moment to teleport.
                  v.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                     v.getYRot(), v.getXRot());
                  v.startSleeping(pos);
                  setActivity(v, "sleeping");
                  long day = level.getGameTime() / 24000L;
                  MemoryStore.write(v, "schedule", day, "I climbed into bed for the night.");
                  VerboseLog.write("SCHED_SLEEP_OK", "villager=" + name
                     + " bedPos=" + pos.toShortString()
                     + " villagerPos=" + v.blockPosition().toShortString()
                     + " distSq=" + String.format("%.2f", d) + " dy=" + String.format("%.2f", dy)
                     + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(bedState.getBlock()).getPath(), "");
               } catch (Exception ex) {
                  setActivity(v, "at_home");
                  VerboseLog.write("SCHED_SLEEP_FAIL", "villager=" + name
                     + " pos=" + pos.toShortString() + " isBed=" + isBed,
                     "startSleeping threw: " + ex);
               }
            } else if (!v.getNavigation().isInProgress()) {
               NavCall.moveTo(v, pos, HOME_SPEED, "ScheduleService.sleep");
               setActivity(v, "going_home");
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=sleeping",
                  "walking to bed " + pos.toShortString() + " distSq=" + d + " isBed=" + isBed);
            } else {
               VerboseLog.write("SCHED_TICK", "villager=" + name + " phase=" + phase
                  + " desired=sleeping",
                  "in transit, distSq=" + d + " navTarget="
                  + (v.getNavigation().getTargetPos() == null ? "null"
                     : v.getNavigation().getTargetPos().toShortString()));
            }
         }
         case "waking" -> {
            if (v.isSleeping()) v.stopSleeping();
            if (!"waking".equals(current)) {
               setActivity(v, "waking");
               long day = level.getGameTime() / 24000L;
               MemoryStore.write(v, "schedule", day, "I woke with the dawn.");
            }
         }
         default -> setActivity(v, "idle");
      }
   }

   /** Scan a {@code radius}-block cube around {@code centre} for any
    *  bed block that isn't already claimed (occupied=false). Returns
    *  the foot tile of the first match, or null. Used as fallback
    *  when a villager's claimed HOME bed has been broken. */
   private static BlockPos findNearbyFreeBed(net.minecraft.server.level.ServerLevel level,
                                              BlockPos centre, int radius) {
      // Build a set of beds already claimed by other townsfolk (their HOME
      // memory pos). BedBlock.OCCUPIED only flips while someone's actively
      // sleeping, so without this check two villagers can claim the same
      // bed within the same minute and end up sleeping on top of each
      // other.
      java.util.Set<BlockPos> claimed = new java.util.HashSet<>();
      for (var ent : level.getAllEntities()) {
         if (!(ent instanceof com.yucareux.townfolk.entity.LlmTownsfolk other)) continue;
         var mem = other.getBrain().getMemory(
            net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
         if (mem.isPresent() && mem.get().dimension() == level.dimension()) {
            claimed.add(mem.get().pos());
         }
      }
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               var state = level.getBlockState(cur);
               if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) continue;
               // Foot side only — head side would render the villager
               // half-in-wall.
               if (state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                     != net.minecraft.world.level.block.state.properties.BedPart.FOOT) continue;
               // Skip beds in active use, or already claimed by another townsfolk.
               if (state.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) continue;
               BlockPos pick = cur.immutable();
               if (claimed.contains(pick)) continue;
               return pick;
            }
         }
      }
      return null;
   }

   private static void setActivity(Villager v, String next) {
      ACTIVITY.put(v.getUUID(), next);
      if (v instanceof com.yucareux.townfolk.entity.LlmTownsfolk t) {
         t.setActivity(prettyLabel(next));
      }
   }

   /** Human-friendly label rendered above the villager's head. */
   private static String prettyLabel(String activity) {
      return switch (activity) {
         case "waking"        -> "(waking up)";
         case "going_to_work" -> "(off to work)";
         case "at_work"       -> "(at work)";
         case "going_home"    -> "(heading home)";
         case "at_home"       -> "(at home)";
         case "sleeping"      -> "(sleeping)";
         case "following"     -> "(following)";
         default              -> "";
      };
   }

   private static String nameOf(Villager v) {
      return v.hasCustomName() ? v.getCustomName().getString() : v.getUUID().toString().substring(0, 8);
   }

   /** True if the current world dayTime falls in the work-hours window
    *  (1000–9000). External services (ParcelRoutine) use this to yield
    *  outside the workday so the villager goes home / sleeps instead of
    *  perpetually starting new tasks. */
   public static boolean isWorkHours(net.minecraft.world.level.Level level) {
      return "work_hours".equals(phaseOf(level.getDayTime() % 24000L));
   }

   /** True during phases when villagers are expected to be heading
    *  home or asleep. Used by services that drive active behaviour
    *  (e.g. need-hailing) to avoid yanking villagers out of bed. */
   public static boolean isSleepTime(net.minecraft.world.level.Level level) {
      String p = phaseOf(level.getDayTime() % 24000L);
      return "night".equals(p) || "dusk".equals(p);
   }

   private static String phaseOf(long dayTime) {
      if (dayTime < 1000) return "dawn";
      if (dayTime < 9000) return "work_hours";
      if (dayTime < 12000) return "evening";
      if (dayTime < 13000) return "dusk";
      return "night";
   }

   private static String desiredActivity(LlmVillagerComponent comp, String phase) {
      return switch (phase) {
         case "dawn"       -> "waking";
         case "work_hours" -> comp.playerSetJob() ? "going_to_work" : "idle";
         case "evening"    -> comp.playerSetHome() ? "going_home" : "idle";
         case "dusk"       -> comp.playerSetHome() ? "going_home" : "idle";
         case "night"      -> comp.playerSetHome() ? "sleeping" : "idle";
         default            -> "idle";
      };
   }

   private ScheduleService() {}
}

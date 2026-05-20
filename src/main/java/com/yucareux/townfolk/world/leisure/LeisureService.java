package com.yucareux.townfolk.world.leisure;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.building.BuildingRegistry;
import com.yucareux.townfolk.building.BuildingTemplates;
import com.yucareux.townfolk.building.RecognizedBuilding;
import com.yucareux.townfolk.diag.NavCall;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.world.ScheduleService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Leisure scaffolding (Stage 11b). Once per day, shortly before the
 * work_hours → evening transition, picks a {@link LeisureActivity}
 * for every alive villager with a home, dispatches movement, and
 * yields to {@link ScheduleService} once dusk arrives so the
 * villager makes bed in time.
 *
 * <p>Selection in this stage is heuristic-weighted random via
 * {@link BiasEvaluator}. Stage 11c replaces the random sampler
 * with an LLM tool call (keeping the random as offline fallback).
 *
 * <p>Tracking is in-memory only. Server restart clears all
 * leisure plans; villagers fall through to ScheduleService's
 * default going_home behavior until the next evening rolls.
 * Choice history that matters for narrative is persisted via
 * {@link MemoryStore} (tag {@code leisure}).
 *
 * <p>Interaction with ScheduleService: {@link #hasActivePlan(UUID)}
 * exposes whether a villager is currently on leisure, so
 * ScheduleService can step aside during evening. Dusk and later
 * phases always belong to ScheduleService — the villager goes
 * home for bed regardless of where they were.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class LeisureService {

   /** Poll every 2 seconds — plenty often for decision + walk dispatch. */
   private static final int POLL_TICKS = 40;

   /** Gametime within the day at which we want to pre-fire the leisure
    *  decision. work_hours ends at 9000; firing at 8500 gives a 500-tick
    *  (~25 s) head start so the villager begins walking right as the
    *  evening phase opens. */
   private static final long DECISION_TICK = 8500L;

   /** Movement speed for leisure walks. Slightly below WORK_SPEED so
    *  leisure looks like ambling, not commuting. */
   private static final double LEISURE_SPEED = 0.45;

   /** WALK activity re-rolls its wander target every this many ticks. */
   private static final long WALK_REROLL_TICKS = 400L;     // 20 s

   /** WALK target radius from the villager's current position. */
   private static final int WALK_RADIUS = 18;

   /** Per-villager current choice. Cleared at day rollover. */
   private static final Map<UUID, LeisureChoice> CHOICES = new ConcurrentHashMap<>();

   /** Gametime of the next WALK re-roll per villager. */
   private static final Map<UUID, Long> NEXT_WALK_REROLL = new ConcurrentHashMap<>();

   /** Per-villager flag: an LLM decision is in flight for today, so
    *  don't re-fire if the poll comes around again before the response
    *  arrives. Cleared on day rollover. */
   private static final Map<UUID, Long> LLM_REQUESTED_DAY = new ConcurrentHashMap<>();

   private LeisureService() {}

   /** True iff this villager has a leisure plan in flight for the
    *  current day. Consulted by {@link ScheduleService} to step
    *  aside during evening. */
   public static boolean hasActivePlan(UUID villager) {
      return CHOICES.containsKey(villager);
   }

   /** Read the current choice (mostly for diagnostics / log lines). */
   public static LeisureChoice currentChoice(UUID villager) {
      return CHOICES.get(villager);
   }

   /** Drop a villager's plan — called by {@link ScheduleService} when
    *  the day rolls over (dawn), and on demand if anything needs to
    *  force-cancel. */
   public static void clearChoice(UUID villager) {
      CHOICES.remove(villager);
      NEXT_WALK_REROLL.remove(villager);
      LLM_REQUESTED_DAY.remove(villager);
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      long now = level.getGameTime();
      if (now % POLL_TICKS != 0L) return;

      long dayTick = now % 24000L;
      long today = now / 24000L;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         TownData data = town.getTown();
         List<RecognizedBuilding> activeTaverns = activeTavernsInLevel(level);

         for (VillagerEntry entry : data.villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());

            // ── Day rollover — clear stale plans so a new evening rolls fresh. ──
            LeisureChoice existing = CHOICES.get(v.getUUID());
            if (existing != null && existing.day() != today) {
               clearChoice(v.getUUID());
               existing = null;
            }

            // ── Decision window: late work_hours, no plan yet, has a home. ──
            // Without a home a villager has nowhere to fall back to at dusk;
            // simpler to skip leisure entirely.
            if (existing == null
                && dayTick >= DECISION_TICK
                && dayTick < 9500L                     // small grace into evening
                && comp.playerSetHome()) {
               // 1) Heuristic decision NOW — drives movement immediately so
               //    we don't wait on LLM latency. The choice may be replaced
               //    in flight if the LLM disagrees (villager "changes their
               //    mind"); narratively fine.
               LeisureChoice chosen = chooseHeuristic(level, v, comp, activeTaverns);
               CHOICES.put(v.getUUID(), chosen);
               existing = chosen;
               onChoiceMade(level, town, v, entry, chosen, false);

               // 2) Fire LLM async — if it returns a different option,
               //    overwrite the choice + re-emit log/memory. Skip the
               //    LLM call if we've already fired one today (guard
               //    against re-poll storms before the response arrives).
               int occupants = countNearTaverns(level, activeTaverns);
               Long llmDay = LLM_REQUESTED_DAY.get(v.getUUID());
               if (llmDay == null || llmDay != today) {
                  LLM_REQUESTED_DAY.put(v.getUUID(), today);
                  final TownSquareBlockEntity finalTown = town;
                  final VillagerEntry finalEntry = entry;
                  final Villager finalV = v;
                  final LeisureActivity heuristicPick = chosen.activity();
                  final long requestedDay = today;
                  LeisureChooser.choose(level, v, entry, comp,
                        activeTaverns, occupants, heuristicPick)
                     .thenAcceptAsync(result -> applyLlmResult(level, finalTown,
                        finalV, finalEntry, requestedDay, result),
                        level.getServer());
               }
            }

            if (existing == null) continue;

            // ── Dusk / night belong to ScheduleService — let it walk them home. ──
            if (dayTick >= 12000L) continue;

            // ── Drive the chosen activity. ──
            executeChoice(level, v, existing, activeTaverns, now);
         }
      }
   }

   // ──────────────────── Selection ────────────────────

   private static LeisureChoice chooseHeuristic(ServerLevel level,
                                                 Villager v,
                                                 LlmVillagerComponent comp,
                                                 List<RecognizedBuilding> taverns) {
      boolean tavernAvailable = !taverns.isEmpty();
      int occupants = countNearTaverns(level, taverns);
      var biases = BiasEvaluator.evaluate(level, v, comp, occupants, tavernAvailable);

      // Weighted random over the scored options.
      LeisureActivity pick = sampleWeighted(level, biases.scores());

      // Reasoning string from the first narration line, or a default.
      String reasoning = biases.narration().isEmpty()
         ? "Just felt like it."
         : biases.narration().get(0);

      // Resolve target position for tavern up front; walk re-rolls each cycle.
      BlockPos target = null;
      if (pick == LeisureActivity.TAVERN && tavernAvailable) {
         target = tavernInteriorCentre(taverns.get(0));
      }
      return new LeisureChoice(level.getGameTime() / 24000L, pick, reasoning, target);
   }

   private static LeisureActivity sampleWeighted(ServerLevel level,
                                                  Map<LeisureActivity, Double> scores) {
      double total = 0.0;
      for (double s : scores.values()) total += s;
      if (total <= 0.0) return LeisureActivity.STAY_HOME;
      double pick = level.getRandom().nextDouble() * total;
      double cumulative = 0.0;
      for (var e : scores.entrySet()) {
         cumulative += e.getValue();
         if (pick <= cumulative) return e.getKey();
      }
      return LeisureActivity.STAY_HOME;
   }

   // ──────────────────── Side effects on choice ────────────────────

   private static void onChoiceMade(ServerLevel level,
                                     TownSquareBlockEntity town,
                                     Villager v,
                                     VillagerEntry entry,
                                     LeisureChoice choice,
                                     boolean fromLlm) {
      long day = level.getGameTime() / 24000L;
      String where = choice.activity().label();
      String name = entry.name();

      // TownLog: short narration line for the player.
      String line = switch (choice.activity()) {
         case TAVERN    -> name + " heads to the tavern for the evening.";
         case WALK      -> name + " sets out for a walk.";
         case STAY_HOME -> name + " calls it a night and turns in early.";
      };
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO, line);

      // Memory: per-villager persistent record of what they chose.
      MemoryStore.write(v, "leisure", day,
         "After work I chose " + where + ". " + choice.reasoning());

      VerboseLog.write("LEISURE_CHOICE",
         "villager=" + name + " day=" + day + " activity=" + choice.activity().name()
            + " source=" + (fromLlm ? "llm" : "heuristic"),
         "reasoning=" + choice.reasoning());
   }

   /** Applies an LLM-returned decision on the server thread, called
    *  via thenAcceptAsync(executor=level.getServer()). If the LLM
    *  picked the SAME option as the heuristic, only the reasoning
    *  string is enriched (no TownLog spam). If it picked DIFFERENTLY,
    *  the choice is replaced and a follow-up TownLog line is written
    *  ("Klaus changes his mind — heads home instead."). */
   private static void applyLlmResult(ServerLevel level,
                                       TownSquareBlockEntity town,
                                       Villager v,
                                       VillagerEntry entry,
                                       long requestedDay,
                                       LeisureChooser.Result result) {
      if (result == null || !result.fromLlm()) return;
      LeisureChoice existing = CHOICES.get(v.getUUID());
      if (existing == null || existing.day() != requestedDay) return;
      if (existing.activity() == result.activity()) {
         // Same pick — just enrich reasoning + memory.
         LeisureChoice updated = new LeisureChoice(existing.day(), existing.activity(),
            result.reasoning(), existing.targetPos());
         CHOICES.put(v.getUUID(), updated);
         long day = level.getGameTime() / 24000L;
         MemoryStore.write(v, "leisure", day,
            "After work I chose " + existing.activity().label() + ". " + result.reasoning());
         VerboseLog.write("LEISURE_LLM_CONFIRM",
            "villager=" + entry.name() + " day=" + day
               + " activity=" + existing.activity().name(),
            "reasoning=" + result.reasoning());
         return;
      }
      // Different pick — override movement, re-emit chat line.
      BlockPos newTarget = null;
      if (result.activity() == LeisureActivity.TAVERN) {
         var taverns = activeTavernsInLevel(level);
         if (!taverns.isEmpty()) newTarget = tavernInteriorCentre(taverns.get(0));
      }
      LeisureChoice updated = new LeisureChoice(existing.day(), result.activity(),
         result.reasoning(), newTarget);
      CHOICES.put(v.getUUID(), updated);
      NEXT_WALK_REROLL.remove(v.getUUID());
      // Cancel any in-flight nav so the new target is picked up cleanly.
      if (v.getNavigation().isInProgress()) v.getNavigation().stop();
      onChoiceMade(level, town, v, entry, updated, true);
   }

   // ──────────────────── Per-activity executors ────────────────────

   private static void executeChoice(ServerLevel level,
                                      Villager v,
                                      LeisureChoice choice,
                                      List<RecognizedBuilding> taverns,
                                      long now) {
      switch (choice.activity()) {
         case TAVERN -> driveTavern(level, v, choice, taverns);
         case WALK   -> driveWalk(level, v, choice, now);
         case STAY_HOME -> driveStayHome(level, v);
      }
   }

   private static void driveTavern(ServerLevel level,
                                    Villager v,
                                    LeisureChoice choice,
                                    List<RecognizedBuilding> taverns) {
      BlockPos target = choice.targetPos();
      if (target == null && !taverns.isEmpty()) {
         target = tavernInteriorCentre(taverns.get(0));
         CHOICES.put(v.getUUID(), choice.withTarget(target));
      }
      if (target == null) {
         // Tavern recognition went away mid-evening — fall back to home.
         CHOICES.put(v.getUUID(),
            new LeisureChoice(choice.day(), LeisureActivity.STAY_HOME,
               "The tavern's no longer recognised — heading home.", null));
         return;
      }
      // If we're already inside the tavern, idle. Otherwise keep walking.
      if (v.blockPosition().closerThan(target, 3.0)) {
         // Stand around — clear any nav so the villager doesn't pace.
         if (v.getNavigation().isInProgress()) v.getNavigation().stop();
      } else if (!v.getNavigation().isInProgress()) {
         NavCall.moveTo(v, target, LEISURE_SPEED, "Leisure.tavern");
      }
   }

   private static void driveWalk(ServerLevel level,
                                  Villager v,
                                  LeisureChoice choice,
                                  long now) {
      Long nextReroll = NEXT_WALK_REROLL.get(v.getUUID());
      BlockPos target = choice.targetPos();
      boolean needsTarget = target == null
         || (nextReroll != null && now >= nextReroll)
         || (target != null && v.blockPosition().closerThan(target, 2.0));
      if (needsTarget) {
         BlockPos here = v.blockPosition();
         int dx = level.getRandom().nextInt(WALK_RADIUS * 2 + 1) - WALK_RADIUS;
         int dz = level.getRandom().nextInt(WALK_RADIUS * 2 + 1) - WALK_RADIUS;
         target = here.offset(dx, 0, dz);
         CHOICES.put(v.getUUID(), choice.withTarget(target));
         NEXT_WALK_REROLL.put(v.getUUID(), now + WALK_REROLL_TICKS);
         NavCall.moveTo(v, target, LEISURE_SPEED, "Leisure.walk");
      } else if (!v.getNavigation().isInProgress()) {
         NavCall.moveTo(v, target, LEISURE_SPEED, "Leisure.walk.resume");
      }
   }

   private static void driveStayHome(ServerLevel level, Villager v) {
      // Explicit no-op — ScheduleService.evening's default going_home path
      // already walks them home. The choice was recorded so memory and
      // the TownLog know the villager opted for a quiet evening.
   }

   // ──────────────────── Helpers ────────────────────

   private static List<RecognizedBuilding> activeTavernsInLevel(ServerLevel level) {
      List<RecognizedBuilding> out = new ArrayList<>();
      for (RecognizedBuilding b : BuildingRegistry.all(level)) {
         if (b.active() && BuildingTemplates.TAVERN.equals(b.templateId())) out.add(b);
      }
      return out;
   }

   /** Centre-of-mass of a recognized tavern's interior. Returns the
    *  marker position if the interior set is empty (defensive). */
   private static BlockPos tavernInteriorCentre(RecognizedBuilding b) {
      var interior = b.interior();
      if (interior == null || interior.isEmpty()) return b.markerPos();
      long sx = 0L, sy = 0L, sz = 0L;
      int n = 0;
      for (BlockPos p : interior) {
         sx += p.getX(); sy += p.getY(); sz += p.getZ();
         n++;
      }
      return new BlockPos((int)(sx / n), (int)(sy / n), (int)(sz / n));
   }

   /** Approximate occupant count across all taverns — sum of villagers
    *  with a TAVERN leisure choice this evening. */
   private static int countNearTaverns(ServerLevel level, List<RecognizedBuilding> taverns) {
      if (taverns.isEmpty()) return 0;
      int n = 0;
      long today = level.getGameTime() / 24000L;
      for (LeisureChoice c : CHOICES.values()) {
         if (c.day() == today && c.activity() == LeisureActivity.TAVERN) n++;
      }
      return n;
   }
}

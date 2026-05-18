package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.dialogue.VillagerBusy;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.entity.LlmTownsfolk;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.llm.PromptBuilder;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Periodic LLM-driven "think" tick that lets villagers act on their own when
 * the player isn't actively prompting them. Strictly budgeted so cost stays
 * predictable.
 *
 * Pacing model:
 *   - Every {@link #SCAN_INTERVAL_TICKS} ticks we scan all towns for eligible
 *     villagers. "Eligible" means their per-villager next-think tick has
 *     arrived AND they're not busy with something else.
 *   - We fire AT MOST {@link #MAX_FIRES_PER_SCAN} LLM calls per scan, picked
 *     by round-robin so 50 villagers in a town don't all think at once.
 *   - On a successful action we set a per-villager cooldown (skip the next
 *     N base-intervals) so the LLM doesn't loop on the same trigger.
 *   - On a failed attempt (LLM error, missing API key, etc.) we set a short
 *     retry-skip so we don't hammer.
 *   - Daily budget: each villager has at most
 *     {@link TownfolkConfig.Common#autonomyDailyBudget} LLM calls per
 *     in-game day. Reset at day rollover.
 *
 * Eligibility gates (skip if any apply):
 *   - autonomy globally disabled (config)
 *   - villager is in an active follow ({@link FollowService})
 *   - villager has a pending block task ({@link BlockTaskQueue})
 *   - villager is sleeping
 *   - villager is in dialogue / exchange ({@link VillagerBusy})
 *   - villager's schedule is suspended (player issued a fresh intent recently)
 *   - daily budget exhausted
 *
 * The system prompt is identical to player-dialogue (full IDENTITY → MEMORY →
 * WORLD SENSE → AGENCY → RECENT ACTION RESULTS), so the LLM has the same
 * grounding it would for an interactive turn. The user message is a small
 * "you have a moment, optionally emit one marker" instruction. Replies
 * containing just "idle" are no-ops and don't trigger cooldown.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class AutonomyService {

   /** How often we look for villagers to fire. Independent of any per-villager
    *  cadence — this just controls the scan rate. */
   private static final int SCAN_INTERVAL_TICKS = 100;       // 5 s

   /** Hard cap on concurrent LLM dispatches per scan, to smooth API load. */
   private static final int MAX_FIRES_PER_SCAN = 1;

   /** When an LLM call fails (model error, network), back off this long
    *  before retrying that villager. */
   private static final int FAILURE_BACKOFF_TICKS = 20 * 30;     // 30 s

   /** Per-villager next-eligible game tick. */
   private static final Map<UUID, Long> NEXT_THINK = new ConcurrentHashMap<>();

   /** Per-villager autonomy spend by day (game-day index → call count). */
   private static final Map<UUID, DayBudget> BUDGET = new ConcurrentHashMap<>();

   private record DayBudget(long day, int calls) {
      DayBudget tick(long currentDay) {
         return day == currentDay ? this : new DayBudget(currentDay, 0);
      }
      DayBudget plusOne() { return new DayBudget(day, calls + 1); }
   }

   public static boolean isExhausted(UUID actor, long currentDay) {
      DayBudget b = BUDGET.get(actor);
      if (b == null) return false;
      if (b.day != currentDay) return false;       // fresh day — not exhausted
      return b.calls >= TownfolkConfig.COMMON.autonomyDailyBudget.get();
   }

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % SCAN_INTERVAL_TICKS != 0L) return;
      if (!TownfolkConfig.COMMON.autonomyEnabled.get()) return;
      if (!LlmClient.get().isConfigured()) return;

      long now = level.getGameTime();
      long currentDay = now / 24000L;
      int fires = 0;

      List<Candidate> candidates = new ArrayList<>();
      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         for (VillagerEntry entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof LlmTownsfolk v)) continue;
            if (!eligible(level, v, currentDay, now)) continue;
            candidates.add(new Candidate(town, entry, v));
         }
      }
      if (candidates.isEmpty()) return;

      // Stagger: prefer the villager with the longest wait since their last
      // think. Falls back to UUID order for stability when ties happen.
      candidates.sort((a, b) -> {
         long ax = NEXT_THINK.getOrDefault(a.entry.uuid(), 0L);
         long bx = NEXT_THINK.getOrDefault(b.entry.uuid(), 0L);
         if (ax != bx) return Long.compare(ax, bx);
         return a.entry.uuid().compareTo(b.entry.uuid());
      });

      for (Candidate c : candidates) {
         if (fires >= MAX_FIRES_PER_SCAN) break;
         if (!VillagerBusy.markBusy(c.entry.uuid())) continue;
         fireOne(level, c, now, currentDay);
         fires++;
      }
   }

   private record Candidate(TownSquareBlockEntity town, VillagerEntry entry, LlmTownsfolk villager) {}

   private static boolean eligible(ServerLevel level, LlmTownsfolk v, long currentDay, long now) {
      UUID id = v.getUUID();
      // Pacing gate.
      Long nextOk = NEXT_THINK.get(id);
      if (nextOk != null && now < nextOk) return false;
      // Daily budget.
      if (isExhausted(id, currentDay)) return false;
      // Busy with something else.
      if (VillagerBusy.isBusy(id)) return false;
      if (FollowService.peek(id).isPresent()) return false;
      if (BlockTaskQueue.hasPending(id)) return false;
      if (v.isSleeping()) return false;
      if (ScheduleService.isSuspended(level, id)) return false;
      // Component sanity: must have a town and a backstory to think coherently.
      LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
      if (c.townSquarePos() == 0L) return false;
      if (c.backstory() == null || c.backstory().isBlank()) return false;
      return true;
   }

   private static void fireOne(ServerLevel level, Candidate c, long now, long currentDay) {
      LlmTownsfolk villager = c.villager;
      VillagerEntry entry = c.entry;
      TownData townData = c.town.getTown();
      LlmVillagerComponent comp = villager.getData(ModRegistries.LLM_VILLAGER.get());

      VerboseLog.write("AUTONOMY_TICK", "actor=" + entry.name() + " day=" + currentDay, "");

      // Pre-bump the budget so concurrent scans don't double-spend.
      BUDGET.merge(entry.uuid(), new DayBudget(currentDay, 1),
         (oldB, newB) -> oldB.tick(currentDay).plusOne());

      // RAG retrieval, like dialogue does — keyed by current activity for
      // relevance ("I'm at work, what would I do?").
      String activity = ScheduleService.activityOf(entry.uuid());
      String query = activity + " " + entry.name() + " autonomy decision";

      MinecraftServer server = level.getServer();
      MemoryStore.retrieveAsync(comp, query, 8).whenCompleteAsync((retrieved, retrieveErr) -> {
         if (retrieveErr != null) {
            VerboseLog.write("AUTONOMY_FAIL",
               "actor=" + entry.name() + " stage=retrieve", "error=" + retrieveErr);
            NEXT_THINK.put(entry.uuid(), now + FAILURE_BACKOFF_TICKS);
            VillagerBusy.markFree(entry.uuid());
            return;
         }
         try {
            String system = PromptBuilder.dialogueSystemPrompt(
               entry, comp, townData, currentDay, retrieved);
            String worldSense = WorldSense.describe(villager, level, c.town.getBlockPos(), entry);
            String systemFinal = PromptBuilder.withWorldSense(system, worldSense);

            List<LlmClient.Message> messages = new ArrayList<>();
            messages.add(new LlmClient.Message("system", systemFinal));
            messages.add(new LlmClient.Message("user", PromptBuilder.autonomyUserMessage()));

            LlmClient.get().chat(TownfolkConfig.COMMON.dialogueModel.get(), messages)
               .whenCompleteAsync((result, chatErr) -> {
                  try {
                     if (chatErr != null) {
                        VerboseLog.write("AUTONOMY_FAIL",
                           "actor=" + entry.name() + " stage=chat", "error=" + chatErr);
                        // Refund the pre-bumped budget slot on network
                        // failure too (the application-error path below
                        // already does this) — keeps accounting
                        // consistent regardless of which kind of error
                        // killed the call.
                        BUDGET.computeIfPresent(entry.uuid(),
                           (k, b) -> b.day == currentDay && b.calls > 0
                              ? new DayBudget(currentDay, b.calls - 1) : b);
                        NEXT_THINK.put(entry.uuid(), now + FAILURE_BACKOFF_TICKS);
                        return;
                     }
                  if (!result.ok()) {
                     VerboseLog.write("AUTONOMY_FAIL",
                        "actor=" + entry.name(), "error=" + result.error());
                     // Back off but DON'T claim a budget slot for a failed call.
                     BUDGET.computeIfPresent(entry.uuid(),
                        (k, b) -> b.day == currentDay && b.calls > 0
                           ? new DayBudget(currentDay, b.calls - 1) : b);
                     NEXT_THINK.put(entry.uuid(), now + FAILURE_BACKOFF_TICKS);
                     return;
                  }
                  String raw = result.content() == null ? "" : result.content().trim();
                  String idleLower = raw.toLowerCase(java.util.Locale.ROOT);
                  boolean trivialIdle = idleLower.equals("idle")
                     || idleLower.equals("\"idle\"")
                     || idleLower.startsWith("idle.")
                     || idleLower.startsWith("idle ");

                  // Bill regardless (the call happened) but don't write memory
                  // for trivial idles to avoid clutter.
                  Entity fresh = level.getEntity(entry.uuid());
                  if (fresh != null) {
                     LlmVillagerComponent now2 = fresh.getData(ModRegistries.LLM_VILLAGER.get());
                     fresh.setData(ModRegistries.LLM_VILLAGER.get(),
                        now2.withUsage(result.inputTokens(), result.outputTokens()));
                  }

                  if (trivialIdle) {
                     NEXT_THINK.put(entry.uuid(),
                        now + TownfolkConfig.COMMON.autonomyIntervalTicks.get());
                     VerboseLog.write("AUTONOMY_IDLE",
                        "actor=" + entry.name() + " inTok=" + result.inputTokens()
                           + " outTok=" + result.outputTokens(), raw);
                     return;
                  }

                  // Parse markers and execute. Mirrors VillagerExchangeService's
                  // pattern: INTENT first, then ACTION (so e.g. "walk to ... THEN
                  // harvest" works in either order).
                  var intentParse = IntentExecutor.parse(raw);
                  var actionParse = ToolDispatcher.parse(intentParse.cleanedText());
                  String cleaned = actionParse.cleanedText().trim();

                  if (level.getEntity(entry.uuid()) instanceof Villager actor) {
                     for (String it : intentParse.intents()) {
                        IntentExecutor.execute(level, c.town, actor, entry, it);
                     }
                     for (String act : actionParse.actions()) {
                        ToolDispatcher.execute(level, c.town, actor, entry, act);
                     }
                  }

                  // Memory write for the reasoning (one line), if non-empty.
                  if (!cleaned.isEmpty() && cleaned.length() < 280) {
                     MemoryStore.write(villager, "autonomy", currentDay,
                        "On my own initiative I thought: \"" + cleaned + "\"");
                  }

                  // Truncate before logging — a degenerate LLM output (model
                  // looping "harvest wild carrots" for 4k tokens) once cost
                  // us the network channel by overflowing the 4k log codec
                  // budget. Show only the first 200 chars in the town log.
                  String shown = cleaned.isEmpty() ? "(silent)"
                     : (cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned);
                  townData.log().add(level.getGameTime(), TownLog.Level.INFO,
                     entry.name() + " (autonomy): " + shown);
                  VerboseLog.write("AUTONOMY_ACT",
                     "actor=" + entry.name() + " intents=" + intentParse.intents().size()
                        + " actions=" + actionParse.actions().size()
                        + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
                     raw);

                  // Apply cooldown only when something actually happened.
                  boolean acted = !intentParse.intents().isEmpty() || !actionParse.actions().isEmpty();
                  long delay = acted
                     ? TownfolkConfig.COMMON.autonomyCooldownTicks.get()
                     : TownfolkConfig.COMMON.autonomyIntervalTicks.get();
                  NEXT_THINK.put(entry.uuid(), now + delay);
               } finally {
                  VillagerBusy.markFree(entry.uuid());
               }
            }, server);
         } catch (Throwable t) {
            // Anything synchronous between retrieve and chat — prompt builder
            // NPE, recipe lookup crash, whatever — frees the lock so the
            // villager doesn't get stuck "in conversation" forever.
            VerboseLog.write("AUTONOMY_FAIL",
               "actor=" + entry.name() + " stage=outer", "error=" + t);
            NEXT_THINK.put(entry.uuid(), now + FAILURE_BACKOFF_TICKS);
            VillagerBusy.markFree(entry.uuid());
         }
      }, server);
   }

   /** Test hook / admin reset. */
   public static void clear() {
      NEXT_THINK.clear();
      BUDGET.clear();
   }

   private AutonomyService() {}
}

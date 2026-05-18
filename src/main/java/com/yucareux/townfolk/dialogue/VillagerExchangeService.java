package com.yucareux.townfolk.dialogue;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.llm.PromptBuilder;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Periodically attempts to start a brief in-character exchange between two
 * villagers in the same town who happen to be near each other.
 *
 *   Trigger:     every 15 real seconds per loaded level (300 server ticks)
 *   Eligibility: both alive, both have backstory loaded, neither is busy,
 *                within {@link #PROXIMITY_BLOCKS}, under per-pair and
 *                per-villager daily caps
 *   Exchange:    turn-by-turn LLM calls alternating between the two
 *                villagers, up to {@link #MAX_TURNS_PER_EXCHANGE}. Each turn
 *                sees identity, memory, town context, and remaining-budget
 *                numbers so the LLM can pace and exit gracefully with
 *                [EXIT].
 *   Result:      one episodic event appended to each participant summarising
 *                their own contribution; counters incremented.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class VillagerExchangeService {

   private static final int EXCHANGE_TICKS = 300;             // 15 s
   private static final int MAX_TURNS_PER_EXCHANGE = 2;          // 1 turn each speaker
   private static final int MAX_EXCHANGES_PER_PAIR_DAY = 6;
   private static final int MAX_EXCHANGES_PER_VILLAGER_DAY = 10;
   private static final int MAX_EXCHANGES_PER_TOWN_DAY = 10;     // global town cap
   private static final double PROXIMITY_BLOCKS = 16.0;
   private static final long PAIR_COOLDOWN_TICKS = 20L * 60 * 4;  // 4 real minutes
   private static final String EXIT_MARKER = "[EXIT]";

   public record Turn(UUID speakerUuid, String text) {}

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % EXCHANGE_TICKS != 0L) return;
      if (!LlmClient.get().isConfigured()) return;
      long day = level.getGameTime() / 24000L;
      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         town.getTown().rolloverExchangeDayIfNeeded(day);
         attemptForTown(level, town, day);
      }
   }

   private static void attemptForTown(ServerLevel level, TownSquareBlockEntity town, long day) {
      TownData data = town.getTown();
      if (data.exchangesTotalToday() >= MAX_EXCHANGES_PER_TOWN_DAY) {
         return;   // global town budget exhausted for the day
      }
      List<VillagerEntry> candidates = new ArrayList<>();
      for (VillagerEntry e : data.villagers()) {
         if (!e.alive()) continue;
         if (VillagerBusy.isBusy(e.uuid())) continue;
         if (data.exchangesForVillager(e.uuid()) >= MAX_EXCHANGES_PER_VILLAGER_DAY) continue;
         Entity ent = level.getEntity(e.uuid());
         if (ent == null) continue;
         LlmVillagerComponent c = ent.getData(ModRegistries.LLM_VILLAGER.get());
         if (c.backstory() == null || c.backstory().isBlank()) continue;
         candidates.add(e);
      }
      if (candidates.size() < 2) {
         if (data.villagers().size() >= 2) {
            data.log().add(level.getGameTime(), TownLog.Level.INFO,
               "exchange tick: only " + candidates.size() + " eligible villager(s) — skipped");
         }
         return;
      }

      List<VillagerEntry[]> pairs = new ArrayList<>();
      for (int i = 0; i < candidates.size(); i++) {
         Entity ea = level.getEntity(candidates.get(i).uuid());
         if (ea == null) continue;
         for (int j = i + 1; j < candidates.size(); j++) {
            Entity eb = level.getEntity(candidates.get(j).uuid());
            if (eb == null) continue;
            if (ea.distanceToSqr(eb) > PROXIMITY_BLOCKS * PROXIMITY_BLOCKS) continue;
            if (data.exchangesBetween(candidates.get(i).uuid(), candidates.get(j).uuid()) >= MAX_EXCHANGES_PER_PAIR_DAY) continue;
            long last = data.lastExchangeTick(candidates.get(i).uuid(), candidates.get(j).uuid());
            if (level.getGameTime() - last < PAIR_COOLDOWN_TICKS) continue;
            pairs.add(new VillagerEntry[]{candidates.get(i), candidates.get(j)});
         }
      }
      if (pairs.isEmpty()) {
         data.log().add(level.getGameTime(), TownLog.Level.INFO,
            "exchange tick: no pairs within " + (int) PROXIMITY_BLOCKS + " blocks of each other");
         return;
      }

      VillagerEntry[] picked = pairs.get(level.getRandom().nextInt(pairs.size()));
      data.log().add(level.getGameTime(), TownLog.Level.EXCHANGE,
         "starting: " + picked[0].name() + " ↔ " + picked[1].name());
      com.yucareux.townfolk.diag.VerboseLog.write("EXCHANGE_START",
         "a=" + picked[0].name() + " b=" + picked[1].name() + " day=" + day, "");
      startExchange(level, town, picked[0], picked[1], day);
   }

   private static void startExchange(ServerLevel level, TownSquareBlockEntity town,
                                     VillagerEntry a, VillagerEntry b, long day) {
      if (!VillagerBusy.markBusy(a.uuid())) return;
      if (!VillagerBusy.markBusy(b.uuid())) { VillagerBusy.markFree(a.uuid()); return; }

      town.getTown().recordExchange(a.uuid(), b.uuid(), level.getGameTime());
      town.setChanged();

      runTurn(level, town, a, b, a, new ArrayList<>(), day);
   }

   private static void runTurn(ServerLevel level, TownSquareBlockEntity town,
                               VillagerEntry a, VillagerEntry b, VillagerEntry currentSpeaker,
                               List<Turn> transcript, long day) {
      if (transcript.size() >= MAX_TURNS_PER_EXCHANGE) {
         finishExchange(level, town, a, b, transcript, day);
         return;
      }
      Entity speakerEntity = level.getEntity(currentSpeaker.uuid());
      VillagerEntry listener = currentSpeaker.uuid().equals(a.uuid()) ? b : a;
      Entity listenerEntity = level.getEntity(listener.uuid());
      if (speakerEntity == null || listenerEntity == null) {
         finishExchange(level, town, a, b, transcript, day);
         return;
      }
      LlmVillagerComponent component = speakerEntity.getData(ModRegistries.LLM_VILLAGER.get());

      int turnsRemaining = MAX_TURNS_PER_EXCHANGE - transcript.size();
      int pairRemaining = MAX_EXCHANGES_PER_PAIR_DAY - town.getTown().exchangesBetween(a.uuid(), b.uuid());
      int totalRemaining = MAX_EXCHANGES_PER_VILLAGER_DAY - town.getTown().exchangesForVillager(currentSpeaker.uuid());
      boolean isInitiator = transcript.isEmpty();

      String worldSense = com.yucareux.townfolk.world.WorldSense.describe(
         speakerEntity, level, town.getBlockPos(), currentSpeaker);

      // Query the speaker's RAG store with the listener's last turn (for
      // mid-exchange) or the listener's name (first turn).
      String query;
      if (transcript.isEmpty()) {
         query = "conversation with " + listener.name();
      } else {
         Turn last = null;
         for (int i = transcript.size() - 1; i >= 0; i--) {
            if (!transcript.get(i).speakerUuid().equals(currentSpeaker.uuid())) {
               last = transcript.get(i); break;
            }
         }
         query = last == null ? listener.name() : last.text();
      }

      final int turnsRemainingF = turnsRemaining;
      final int pairRemainingF = pairRemaining;
      final int totalRemainingF = totalRemaining;
      final boolean isInitiatorF = isInitiator;
      com.yucareux.townfolk.villager.MemoryStore.retrieveAsync(component, query, 8).thenAcceptAsync(retrieved -> {
         com.yucareux.townfolk.diag.VerboseLog.write("RAG_INJECT",
            "villager=" + currentSpeaker.name() + " context=exchange count=" + retrieved.size(),
            "query=" + query);
         String system = PromptBuilder.villagerExchangeSystemPrompt(
            currentSpeaker, listener, component, town.getTown(), day,
            turnsRemainingF, pairRemainingF, totalRemainingF, isInitiatorF, retrieved);
         String systemFinal = PromptBuilder.withWorldSense(system, worldSense);

         List<LlmClient.Message> messages = new ArrayList<>();
         messages.add(new LlmClient.Message("system", systemFinal));
         if (transcript.isEmpty()) {
            messages.add(new LlmClient.Message("user", "[You spot " + listener.name() + " nearby. Begin.]"));
         } else {
            for (Turn t : transcript) {
               String role = t.speakerUuid().equals(currentSpeaker.uuid()) ? "assistant" : "user";
               messages.add(new LlmClient.Message(role, t.text()));
            }
         }

         fireTurnLlm(level, town, a, b, currentSpeaker, listener, transcript, day, messages);
      }, level.getServer());
      return;
   }

   /** Continuation extracted so the retrieveAsync chain can call back into the LLM step. */
   private static void fireTurnLlm(ServerLevel level, TownSquareBlockEntity town,
                                   VillagerEntry a, VillagerEntry b, VillagerEntry currentSpeaker,
                                   VillagerEntry listener, List<Turn> transcript, long day,
                                   List<LlmClient.Message> messages) {

      LlmClient.get().chat(TownfolkConfig.COMMON.dialogueModel.get(), messages)
         .thenAcceptAsync(result -> {
            if (!result.ok()) {
               Townfolk.LOGGER.warn("Exchange turn failed for {}: {}", currentSpeaker.name(), result.error());
               finishExchange(level, town, a, b, transcript, day);
               return;
            }
            String rawText = result.content() == null ? "" : result.content().trim();
            boolean exit = rawText.contains(EXIT_MARKER);
            // Strip control markers in this order: INTENT, ACTION, then EXIT.
            var intentParse = com.yucareux.townfolk.world.IntentExecutor.parse(rawText);
            var actionParse = com.yucareux.townfolk.world.ToolDispatcher.parse(intentParse.cleanedText());
            String cleanText = actionParse.cleanedText().replace(EXIT_MARKER, "").trim();

            // Execute intents/actions for the speaker BEFORE the next turn fires.
            Entity speakerFresh = level.getEntity(currentSpeaker.uuid());
            if (speakerFresh instanceof net.minecraft.world.entity.npc.Villager speakerVillager) {
               for (String it : intentParse.intents()) {
                  com.yucareux.townfolk.world.IntentExecutor.execute(level, town, speakerVillager, currentSpeaker, it);
               }
               for (String act : actionParse.actions()) {
                  com.yucareux.townfolk.world.ToolDispatcher.execute(level, town, speakerVillager, currentSpeaker, act);
               }
            }

            if (!cleanText.isEmpty()) {
               transcript.add(new Turn(currentSpeaker.uuid(), cleanText));
               String shown = cleanText.length() > 160 ? cleanText.substring(0, 160) + "…" : cleanText;
               town.getTown().log().add(level.getGameTime(), TownLog.Level.EXCHANGE,
                  currentSpeaker.name() + ": \"" + shown + "\"" + (exit ? " [EXIT]" : ""));
               com.yucareux.townfolk.diag.VerboseLog.write("EXCHANGE_TURN",
                  "speaker=" + currentSpeaker.name() + " listener=" + listener.name()
                     + " turn=" + transcript.size() + " exit=" + exit
                     + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
                  cleanText);
            }

            // Bill the usage on the speaker's component.
            Entity fresh = level.getEntity(currentSpeaker.uuid());
            if (fresh != null) {
               LlmVillagerComponent now = fresh.getData(ModRegistries.LLM_VILLAGER.get());
               fresh.setData(ModRegistries.LLM_VILLAGER.get(),
                  now.withUsage(result.inputTokens(), result.outputTokens()));
            }

            if (exit || transcript.size() >= MAX_TURNS_PER_EXCHANGE) {
               finishExchange(level, town, a, b, transcript, day);
            } else {
               VillagerEntry next = currentSpeaker.uuid().equals(a.uuid()) ? b : a;
               runTurn(level, town, a, b, next, transcript, day);
            }
         }, level.getServer());
   }

   private static void finishExchange(ServerLevel level, TownSquareBlockEntity town,
                                      VillagerEntry a, VillagerEntry b, List<Turn> transcript, long day) {
      town.getTown().log().add(level.getGameTime(), TownLog.Level.EXCHANGE,
         "ended: " + a.name() + " ↔ " + b.name() + " (" + transcript.size() + " turns)");

      if (transcript.isEmpty() || !LlmClient.get().isConfigured()) {
         VillagerBusy.markFree(a.uuid());
         VillagerBusy.markFree(b.uuid());
         return;
      }

      // Build a readable transcript for the summarizer.
      StringBuilder ts = new StringBuilder();
      for (Turn t : transcript) {
         VillagerEntry who = t.speakerUuid().equals(a.uuid()) ? a : b;
         ts.append(who.name()).append(": ").append(t.text()).append('\n');
      }
      String transcriptText = ts.toString();
      com.yucareux.townfolk.diag.VerboseLog.write("EXCHANGE_SUMMARIZE_INPUT",
         "a=" + a.name() + " b=" + b.name() + " day=" + day, transcriptText);

      var messages = com.yucareux.townfolk.llm.PromptBuilder.exchangeSummaryMessages(
         a, b, town.getTown(), day, transcriptText);

      LlmClient.get().chat(
         com.yucareux.townfolk.config.TownfolkConfig.COMMON.dialogueModel.get(), messages)
         .thenAcceptAsync(result -> {
            try {
               if (!result.ok()) {
                  Townfolk.LOGGER.warn("Exchange summarizer failed: {}", result.error());
                  town.getTown().log().add(level.getGameTime(), TownLog.Level.WARN,
                     "summarizer failed for " + a.name() + " ↔ " + b.name() + ": " + result.error());
                  // Fall back to thin heuristic memory so we don't lose the chat entirely.
                  fallbackOwnEvent(level, a, b, transcript, day);
                  fallbackOwnEvent(level, b, a, transcript, day);
                  return;
               }
               String response = result.content() == null ? "" : result.content();
               com.yucareux.townfolk.diag.VerboseLog.write("EXCHANGE_SUMMARIZE_OUTPUT",
                  "a=" + a.name() + " b=" + b.name()
                     + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
                  response);

               String shared = section(response, "SHARED SUMMARY");
               if (shared == null || shared.isBlank()) shared = section(response, "SUMMARY");
               String promises = section(response, "PROMISES");
               String reactionA = section(response, a.name().toUpperCase(java.util.Locale.ROOT) + " REACTION");
               String reactionB = section(response, b.name().toUpperCase(java.util.Locale.ROOT) + " REACTION");

               applyMemory(level, a, b, shared, reactionA, promises, day);
               applyMemory(level, b, a, shared, reactionB, promises, day);

               // Billing: split between the two villagers (one shared call).
               long inHalf = result.inputTokens() / 2;
               long outHalf = result.outputTokens() / 2;
               billUsage(level, a.uuid(), inHalf, outHalf);
               billUsage(level, b.uuid(), result.inputTokens() - inHalf, result.outputTokens() - outHalf);

               town.getTown().log().add(level.getGameTime(), TownLog.Level.EXCHANGE,
                  "summarized: " + a.name() + " ↔ " + b.name()
                     + (promises != null && !promises.isBlank() ? " (+pins)" : ""));
            } finally {
               VillagerBusy.markFree(a.uuid());
               VillagerBusy.markFree(b.uuid());
            }
         }, level.getServer());
   }

   private static void applyMemory(ServerLevel level, VillagerEntry self, VillagerEntry other,
                                   String sharedSummary, String reaction, String promises, long day) {
      Entity entity = level.getEntity(self.uuid());
      if (entity == null) return;
      LlmVillagerComponent c = entity.getData(ModRegistries.LLM_VILLAGER.get());

      if (sharedSummary != null && !sharedSummary.isBlank()) {
         com.yucareux.townfolk.villager.MemoryStore.write(entity, "exchange_summary", day,
            "Spoke with " + other.name() + ": " + sharedSummary.trim());
      }
      if (reaction != null && !reaction.isBlank()) {
         com.yucareux.townfolk.villager.MemoryStore.write(entity, "exchange_summary", day,
            "(felt) " + reaction.trim());
      }
      if (promises != null && !promises.isBlank()) {
         var nextTodos = new java.util.ArrayList<>(c.todos());
         java.util.Set<String> existing = new java.util.HashSet<>();
         for (var existingT : nextTodos)
            if (existingT.isOpen()) existing.add(existingT.text().trim().toLowerCase(java.util.Locale.ROOT));
         for (String line : promises.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("-") || t.startsWith("•") || t.startsWith("*")) t = t.substring(1).trim();
            if (t.isEmpty()) continue;
            // Reshape "Maker → Recipient: thing" into a todo from self's POV.
            String todoText = frameTodoForSelf(self, other, t);
            if (todoText == null) continue;
            String key = todoText.toLowerCase(java.util.Locale.ROOT);
            if (existing.contains(key)) continue;
            nextTodos.add(new com.yucareux.townfolk.villager.Todo(
               java.util.UUID.randomUUID().toString(), todoText, other.name(), "open", day));
            existing.add(key);
         }
         c = c.withTodos(nextTodos);
      }
      entity.setData(ModRegistries.LLM_VILLAGER.get(), c);
   }

   /**
    * Rewrite "Maker → Recipient: thing" into a todo-friendly imperative
    * for the given {@code self}:
    *   self is maker     → "fix Claus's fence by midday"
    *   self is recipient → "Awaiting Claus: bring me firewood"
    */
   private static String frameTodoForSelf(VillagerEntry self, VillagerEntry other, String raw) {
      int sep = raw.indexOf("→");
      if (sep < 0) sep = raw.indexOf("->");
      if (sep < 0) return null;
      int colon = raw.indexOf(':', sep);
      if (colon < 0) return null;
      String maker = raw.substring(0, sep).trim();
      String recipient = raw.substring(sep + (raw.charAt(sep) == '→' ? 1 : 2), colon).trim();
      String content = raw.substring(colon + 1).trim();
      if (content.isEmpty()) return null;
      boolean selfIsMaker = maker.equalsIgnoreCase(self.name());
      boolean selfIsRecipient = recipient.equalsIgnoreCase(self.name());
      if (!selfIsMaker && !selfIsRecipient) return null;
      return selfIsMaker
         ? content                                        // first-person actionable
         : "Awaiting " + other.name() + ": " + content;   // passively tracked
   }

   private static void billUsage(ServerLevel level, java.util.UUID uuid, long in, long out) {
      Entity e = level.getEntity(uuid);
      if (e == null) return;
      LlmVillagerComponent c = e.getData(ModRegistries.LLM_VILLAGER.get());
      e.setData(ModRegistries.LLM_VILLAGER.get(), c.withUsage(in, out));
   }

   /** Used only when the summarizer call itself fails. */
   private static void fallbackOwnEvent(ServerLevel level, VillagerEntry self, VillagerEntry other,
                                        List<Turn> transcript, long day) {
      Entity entity = level.getEntity(self.uuid());
      if (entity == null) return;
      String firstSelf = "", lastSelf = "";
      for (Turn t : transcript) {
         if (!t.speakerUuid().equals(self.uuid())) continue;
         if (firstSelf.isEmpty()) firstSelf = t.text();
         lastSelf = t.text();
      }
      if (firstSelf.length() > 140) firstSelf = firstSelf.substring(0, 140) + "…";
      if (lastSelf.length() > 140) lastSelf = lastSelf.substring(0, 140) + "…";
      String summary = "Chatted with " + other.name() + " (" + transcript.size() + " turns). Opened: \""
         + firstSelf + "\"" + (firstSelf.equals(lastSelf) ? "" : " Ended: \"" + lastSelf + "\"");
      com.yucareux.townfolk.villager.MemoryStore.write(entity, "exchange_summary", day, summary);
   }

   private static String section(String response, String name) {
      return SectionParser.section(response, name);
   }

   private VillagerExchangeService() {}
}

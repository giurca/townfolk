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
import com.yucareux.townfolk.villager.EmbeddedEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import com.yucareux.townfolk.villager.PinnedFact;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Nightly self-reflection. Triggers on in-game day rollover. For each
 * villager that accumulated memories since their last compaction, feeds
 * those memories to the LLM and updates Beliefs + optionally extracts new
 * Pins. The summary itself is then written back as a {@code reflection}
 * memory entry — future retrievals can surface "I reflected on day 7 that…"
 *
 * Memories themselves stay in the RAG store. We do NOT delete the day's raw
 * entries after compaction — the embeddings remain retrievable. Beliefs is
 * the cumulative narrative *summary* layered on top.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class NightlyCompactor {

   private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_SEEN_DAY =
      new java.util.concurrent.ConcurrentHashMap<>();
   private static final Set<UUID> IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet();

   @SubscribeEvent
   public static void onLevelTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (level.getGameTime() % 200L != 0L) return;

      long currentDay = level.getGameTime() / 24000L;
      String dimKey = level.dimension().location().toString();
      Long previous = LAST_SEEN_DAY.put(dimKey, currentDay);
      if (previous != null && previous.longValue() == currentDay) return;

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         scheduleTown(level, town, currentDay);
      }
   }

   private static void scheduleTown(ServerLevel level, TownSquareBlockEntity town, long currentDay) {
      TownData data = town.getTown();
      long dayBeingCompacted = currentDay - 1;
      for (VillagerEntry entry : data.villagers()) {
         if (!entry.alive()) continue;
         if (IN_FLIGHT.contains(entry.uuid())) continue;
         Entity entity = level.getEntity(entry.uuid());
         if (entity == null) continue;
         LlmVillagerComponent c = entity.getData(ModRegistries.LLM_VILLAGER.get());
         if (c.lastCompactedDay() == currentDay) continue;

         List<EmbeddedEntry> yesterdaysMemories = MemoryStore.memoriesForDay(c, dayBeingCompacted);
         if (yesterdaysMemories.isEmpty()) {
            // Nothing to reflect on — just advance the watermark.
            entity.setData(ModRegistries.LLM_VILLAGER.get(), c.withLastCompactedDay(currentDay));
            continue;
         }
         if (!LlmClient.get().isConfigured()) continue;
         fireCompaction(level, town, entry, c, yesterdaysMemories, dayBeingCompacted, currentDay);
      }
   }

   private static void fireCompaction(ServerLevel level, TownSquareBlockEntity town,
                                      VillagerEntry entry, LlmVillagerComponent component,
                                      List<EmbeddedEntry> dayMemories,
                                      long dayBeingCompacted, long currentDay) {
      IN_FLIGHT.add(entry.uuid());

      StringBuilder raw = new StringBuilder();
      for (EmbeddedEntry e : dayMemories) {
         raw.append("[").append(e.kind()).append("] ").append(e.text()).append('\n');
      }

      List<LlmClient.Message> messages = PromptBuilder.compactionMessages(
         entry, component, town.getTown(), dayBeingCompacted, raw.toString());

      town.getTown().log().add(level.getGameTime(), TownLog.Level.COMPACT,
         "compacting " + entry.name() + " (day " + dayBeingCompacted + ", " + dayMemories.size() + " memories)");
      com.yucareux.townfolk.diag.VerboseLog.write("COMPACT_INPUT",
         "villager=" + entry.name() + " day=" + dayBeingCompacted + " memories=" + dayMemories.size(),
         raw.toString());

      LlmClient.get().chat(TownfolkConfig.COMMON.dialogueModel.get(), messages)
         .thenAcceptAsync(result -> {
            IN_FLIGHT.remove(entry.uuid());
            if (!result.ok()) {
               Townfolk.LOGGER.warn("Compaction failed for {}: {}", entry.name(), result.error());
               town.getTown().log().add(level.getGameTime(), TownLog.Level.WARN,
                  "compaction failed for " + entry.name() + ": " + result.error());
               return;
            }
            String response = result.content() == null ? "" : result.content();
            com.yucareux.townfolk.diag.VerboseLog.write("COMPACT_RESPONSE",
               "villager=" + entry.name() + " day=" + dayBeingCompacted
                  + " inTok=" + result.inputTokens() + " outTok=" + result.outputTokens(),
               response);

            String summary = section(response, "SUMMARY");
            String beliefs = section(response, "BELIEFS");
            String pins = section(response, "PINS");
            boolean anyContent =
               (summary != null && !summary.isBlank())
               || (beliefs != null && !beliefs.isBlank())
               || (pins != null && !pins.isBlank());

            Entity fresh = level.getEntity(entry.uuid());
            if (fresh == null) return;
            LlmVillagerComponent now = fresh.getData(ModRegistries.LLM_VILLAGER.get());

            // Identify todos that will decay this pass so we can write
            // a regret memory for each — without this, the villager
            // has no record of the broken promise (audit item C, the
            // "silently drop" band-aid was here for a while).
            long decayCutoff = currentDay - LlmVillagerComponent.TODO_DECAY_DAYS;
            for (var t : now.todos()) {
               if (t.isOpen() && t.createdDay() <= decayCutoff) {
                  MemoryStore.write(fresh, "reflection", dayBeingCompacted,
                     "I never got around to: " + t.text()
                        + (t.counterparty() != null && !t.counterparty().isBlank()
                           ? " (promised to " + t.counterparty() + ")"
                           : ""));
               }
            }

            LlmVillagerComponent updated = apply(now, response, dayBeingCompacted, currentDay)
               .withUsage(result.inputTokens(), result.outputTokens());
            fresh.setData(ModRegistries.LLM_VILLAGER.get(), updated);

            // The summary becomes a new retrievable memory tagged as reflection.
            if (summary != null && !summary.isBlank()) {
               MemoryStore.write(fresh, "reflection", dayBeingCompacted, summary.trim());
            }

            // Cosmetic profession re-skin: pick the dominant activity bucket
            // over the last week and apply the matching vanilla profession.
            // Only fires past the vote threshold so brand-new villagers don't
            // wobble between identities. Re-fetch the component AFTER the
            // reflection write above so the tally sees the latest memories.
            if (fresh instanceof net.minecraft.world.entity.npc.Villager vForReskin) {
               var freshComp = vForReskin.getData(ModRegistries.LLM_VILLAGER.get());
               var tally = com.yucareux.townfolk.villager.VillagerIdentity.tally(freshComp, currentDay);
               var profHolder = com.yucareux.townfolk.villager.VillagerIdentity.dominantProfession(tally);
               if (profHolder != null) {
                  var oldData = vForReskin.getVillagerData();
                  var currentProf = oldData.getProfession();
                  var targetProf = profHolder.value();
                  if (currentProf != targetProf) {
                     vForReskin.setVillagerData(oldData.setProfession(targetProf));
                     var key = net.minecraft.core.registries.BuiltInRegistries
                        .VILLAGER_PROFESSION.getKey(targetProf);
                     town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
                        entry.name() + " now looks the part of a "
                           + (key == null ? "townsfolk" : key.getPath()));
                  }
               }
            }

            if (!anyContent) {
               String preview = response.length() > 200 ? response.substring(0, 200) + "…" : response;
               town.getTown().log().add(level.getGameTime(), TownLog.Level.WARN,
                  "compacted " + entry.name() + " — empty response (no SUMMARY/BELIEFS/PINS). raw: " + preview);
            } else {
               int summaryLen = summary == null ? 0 : summary.length();
               int beliefsLen = beliefs == null ? 0 : beliefs.length();
               int pinLines = pins == null || pins.isBlank() ? 0 : pins.split("\\n").length;
               town.getTown().log().add(level.getGameTime(), TownLog.Level.COMPACT,
                  "compacted " + entry.name() + " — summary " + summaryLen + "c, beliefs " + beliefsLen + "c, " + pinLines + " new pins");
            }
         }, level.getServer());
   }

   /** Parse the response and update the component (beliefs, pins, todo-decay, watermarks). */
   static LlmVillagerComponent apply(LlmVillagerComponent current, String response,
                                     long dayBeingCompacted, long currentDay) {
      String beliefs = section(response, "BELIEFS");
      String pinsBlock = section(response, "PINS");

      LlmVillagerComponent c = current.withLastCompactedDay(currentDay);
      if (beliefs != null && !beliefs.isBlank()) c = c.withBeliefs(beliefs);

      if (pinsBlock != null && !pinsBlock.isBlank()) {
         List<PinnedFact> nextPins = new ArrayList<>(c.pinnedFacts());
         Set<String> existing = new HashSet<>();
         for (PinnedFact p : nextPins) existing.add(p.text().trim().toLowerCase());
         for (String line : pinsBlock.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("-") || t.startsWith("•") || t.startsWith("*")) t = t.substring(1).trim();
            if (t.isEmpty()) continue;
            if (existing.contains(t.toLowerCase())) continue;
            nextPins.add(new PinnedFact(UUID.randomUUID().toString(), t, "active", dayBeingCompacted, false));
            existing.add(t.toLowerCase());
         }
         c = c.withPinnedFacts(nextPins);
      }

      c = c.withClearedDialogueHistory();

      // Auto-decay stale open todos. The "things I never got around to" note
      // becomes a reflection memory (written separately by the caller, since
      // we don't have the entity here).
      long cutoff = currentDay - LlmVillagerComponent.TODO_DECAY_DAYS;
      List<com.yucareux.townfolk.villager.Todo> survivors = new ArrayList<>();
      List<com.yucareux.townfolk.villager.Todo> decayed = new ArrayList<>();
      for (var t : c.todos()) {
         if (t.isOpen() && t.createdDay() <= cutoff) decayed.add(t);
         else survivors.add(t);
      }
      if (!decayed.isEmpty()) {
         c = c.withTodos(survivors);
         // The regret-memory write happens in fireCompaction (which
         // has entity access). This method just prunes the list.
      }
      return c;
   }

   private static final String[] COMPACT_SECTIONS = { "SUMMARY", "BELIEFS", "PINS" };

   private static String section(String response, String name) {
      return SectionParser.section(response, name, COMPACT_SECTIONS);
   }

   private NightlyCompactor() {}
}

package com.yucareux.townfolk.llm;

import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.PinnedFact;
import java.util.concurrent.CompletableFuture;

/**
 * Prompts the LLM to write a short backstory paragraph for a newly-spawned
 * villager, grounded in the town's name, the seed text the player provided,
 * AND the town's existing residents and shared history. The result is a
 * backstory that *connects* to the town — shared neighbours, mentioned
 * events, possible relationships — so day-one conversations have something
 * to reference.
 */
public final class PersonaGenerator {

   private static final String SYSTEM_PROMPT = """
      You are writing brief character backstories for NPCs in a Minecraft world
      set in a real-Earth Alpine valley. Backstories are 3-5 sentences of
      plain prose. They should:
        - sound grounded and human, not gamey
        - reference specific concrete details (a place, a relative, an event)
        - fit a pre-industrial / early 20th-century Swiss alpine setting
          unless the seed says otherwise
        - end with a small hook or unresolved thread (a worry, an ambition,
          a regret) that gives the character something to talk about

      CRITICAL: the user prompt lists the OTHER residents of this villager's
      town and any town-shared historical facts. Your backstory should
      naturally connect this new character to ONE OR TWO of those residents
      (an old friendship, a rivalry, a hired-from-them relationship, a
      childhood acquaintance, a shared loss). Do not list every resident —
      pick the most plausible 1-2 connections that fit the seed and roles.
      If there are no other residents, write a self-contained backstory that
      leaves room for relationships to form.

      Return only the backstory text, with no preamble or quotation marks.
      """;

   /** Generate a single fitting first-name for a new villager. Cheap, low-token
    *  call — used when the player leaves the name field blank at spawn time so
    *  they can dump 10 villagers into a town without typing each one. Returns
    *  a single trimmed word; if anything goes wrong we fall back to "Villager"
    *  + a small random suffix at the call site. */
   public static CompletableFuture<LlmClient.LlmResult> generateName(TownData town, String personaSeed) {
      StringBuilder others = new StringBuilder();
      for (VillagerEntry o : town.villagers()) {
         if (!o.alive()) continue;
         if (others.length() > 0) others.append(", ");
         others.append(o.name());
      }
      if (others.length() == 0) others.append("(none yet)");

      String seedClause = personaSeed == null || personaSeed.isBlank()
         ? "no special seed provided"
         : personaSeed;

      String system =
         "You name NPC villagers in a Minecraft Alpine-valley town (Swiss pre-industrial / early 20th c.). "
         + "Reply with ONLY a single given name — one word, no surname, no quotes, no period, no preamble. "
         + "Pick a name that fits the setting (Hans, Anya, Brauhn, Marta, Ueli, Liesl, etc). "
         + "Avoid duplicating any existing residents listed below.";
      String user = "Town: " + town.townName() + "\n"
                  + "Existing residents (do not duplicate): " + others + "\n"
                  + "Persona seed (may inform the name's gender/origin): " + seedClause + "\n"
                  + "Return one name.";
      return LlmClient.get().chat(
         TownfolkConfig.COMMON.dialogueModel.get(),
         system,
         user
      );
   }

   public static CompletableFuture<LlmClient.LlmResult> generateBackstory(VillagerEntry entry, TownData town) {
      String seed = entry.personaSeed() == null || entry.personaSeed().isBlank()
         ? "no special instructions, write whatever fits the setting"
         : entry.personaSeed();

      StringBuilder others = new StringBuilder();
      for (VillagerEntry o : town.villagers()) {
         if (o.uuid().equals(entry.uuid())) continue;
         if (!o.alive()) continue;
         others.append("  - ").append(o.name());
         if (o.role() != null && !o.role().isBlank() && !"resident".equalsIgnoreCase(o.role().trim())) {
            others.append(" (").append(o.role()).append(")");
         }
         if (o.personaSeed() != null && !o.personaSeed().isBlank()) {
            String seedSnippet = o.personaSeed().length() > 80
               ? o.personaSeed().substring(0, 80) + "…" : o.personaSeed();
            others.append(" — ").append(seedSnippet);
         }
         others.append('\n');
      }
      if (others.length() == 0) others.append("  (none — this is the first or only resident)\n");

      StringBuilder facts = new StringBuilder();
      for (PinnedFact f : town.townFacts()) {
         if ("obsolete".equals(f.status())) continue;
         facts.append("  - ").append(f.text()).append('\n');
      }
      if (facts.length() == 0) facts.append("  (none recorded yet)\n");

      String user = String.format("""
         Town: %s
         Villager name: %s
         Seed: %s

         Other residents (consider connecting to 1-2 of these naturally):
         %s
         Town-shared history (use as backdrop if relevant):
         %s
         """, town.townName(), entry.name(), seed, others, facts);

      return LlmClient.get().chat(
         TownfolkConfig.COMMON.backstoryModel.get(),
         SYSTEM_PROMPT,
         user
      );
   }

   private PersonaGenerator() {
   }
}

package com.yucareux.townfolk.world.leisure;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.building.RecognizedBuilding;
import com.yucareux.townfolk.config.TownfolkConfig;
import com.yucareux.townfolk.llm.LlmClient;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.EmbeddedEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/**
 * LLM-driven evening-activity selector for Stage 11c. Wraps
 * {@link LlmClient} with a context-builder + JSON parser tailored
 * for the {@code choose_evening_activity} surface.
 *
 * <p>Contract: the LLM receives the villager's identity, recent
 * leisure history (last 5 days), narrated biases from
 * {@link BiasEvaluator}, and the gated list of available options
 * (excluding {@code tavern} when no active tavern exists). It
 * returns a JSON object:
 * <pre>
 *   {"choice": "tavern", "reasoning": "haven't been in a few days"}
 * </pre>
 *
 * <p>Validation falls back to {@link LeisureActivity#STAY_HOME} for
 * any of: HTTP failure, missing field, unknown choice, choice not
 * in {@code available}. Callers are expected to ALSO compute a
 * heuristic choice and use it as the live answer while the LLM
 * call is in flight — see {@link LeisureService}.
 */
public final class LeisureChooser {

   private LeisureChooser() {}

   /** Build the JSON-shaped prompt + fire the LLM call. Async.
    *
    *  <p>Returns a future of {@link Result} that always resolves
    *  (no throwing) — on any error the result's {@code activity}
    *  is the heuristic provided as {@code fallback}. */
   public static CompletableFuture<Result> choose(ServerLevel level,
                                                   Villager v,
                                                   VillagerEntry entry,
                                                   LlmVillagerComponent comp,
                                                   List<RecognizedBuilding> activeTaverns,
                                                   int tavernOccupants,
                                                   LeisureActivity fallback) {
      Set<LeisureActivity> available = EnumSet.noneOf(LeisureActivity.class);
      if (!activeTaverns.isEmpty()) available.add(LeisureActivity.TAVERN);
      available.add(LeisureActivity.WALK);
      available.add(LeisureActivity.STAY_HOME);

      if (!LlmClient.get().isConfigured()) {
         // No key — fall back to heuristic synchronously.
         return CompletableFuture.completedFuture(new Result(fallback,
            "Just felt like it.", false));
      }

      String system = systemPrompt();
      String user = userPrompt(level, v, entry, comp, activeTaverns,
         tavernOccupants, available);
      return LlmClient.get()
         .chat(TownfolkConfig.COMMON.dialogueModel.get(), system, user)
         .thenApply(result -> parseResult(result, fallback, available, entry.name()));
   }

   // ────────── Prompt construction ──────────

   private static String systemPrompt() {
      return """
         You are role-playing a Minecraft Alpine-village resident deciding what to do this evening between the end of their workday and bedtime.
         You will be told their identity, recent evening choices, current mood/weather context, and the available options.
         Pick ONE option and give a one-sentence in-character reason.
         Respond with strict JSON ONLY — no prose, no markdown, no code fence.
         Schema: {"choice": "<one of the available options>", "reasoning": "<one short sentence, max 24 words, first-person>"}
         The choice MUST be one of the options listed in OPTIONS. If unsure, prefer continuity with the past few evenings or what your backstory suggests.
         """;
   }

   private static String userPrompt(ServerLevel level,
                                     Villager v,
                                     VillagerEntry entry,
                                     LlmVillagerComponent comp,
                                     List<RecognizedBuilding> activeTaverns,
                                     int tavernOccupants,
                                     Set<LeisureActivity> available) {
      StringBuilder sb = new StringBuilder();
      sb.append("Villager: ").append(entry.name())
        .append(" (").append(entry.role() == null ? "resident" : entry.role()).append(")\n");
      if (comp.backstory() != null && !comp.backstory().isBlank()) {
         String s = comp.backstory();
         if (s.length() > 480) s = s.substring(0, 480) + "…";
         sb.append("Backstory: ").append(s).append("\n");
      }
      sb.append("Day: ").append(level.getGameTime() / 24000L).append("\n");
      // Hunger context — one short line per Stage 12c. Skipped when
      // hunger is unremarkable so the LLM isn't told a meaningless
      // "fine" status every evening.
      int hunger = comp.hunger();
      if (hunger <= 15)      sb.append("Hunger: starving — hasn't eaten in days.\n");
      else if (hunger <= 35) sb.append("Hunger: very hungry.\n");
      else if (hunger <= 55) sb.append("Hunger: peckish.\n");

      // Recent leisure history pulled directly from the embedded-entry
      // memory list. We don't need RAG retrieval for this — the last few
      // 'leisure' entries are inherently chronological and tiny.
      List<String> recent = recentLeisureMemories(comp, 5);
      sb.append("Recent evenings:\n");
      if (recent.isEmpty()) sb.append("  (none recorded yet)\n");
      else for (String m : recent) sb.append("  - ").append(m).append('\n');

      // Bias narration from the same evaluator the offline sampler uses.
      var biases = BiasEvaluator.evaluate(level, v, comp, tavernOccupants,
         !activeTaverns.isEmpty());
      sb.append("Tonight's mood / context:\n");
      if (biases.narration().isEmpty()) sb.append("  - (unremarkable evening)\n");
      else for (String n : biases.narration()) sb.append("  - ").append(n).append('\n');

      // Options surface — show only the gated set. Each option gets a
      // brief one-line description so the LLM doesn't have to infer.
      sb.append("OPTIONS:\n");
      if (available.contains(LeisureActivity.TAVERN)) {
         String tavernHint = activeTaverns.isEmpty() ? ""
            : " (currently " + tavernOccupants + " regular(s) heading there tonight)";
         sb.append("  - tavern: walk to the village tavern and pass time there")
           .append(tavernHint).append("\n");
      }
      if (available.contains(LeisureActivity.WALK)) {
         sb.append("  - walk: amble around the town's outskirts for the evening\n");
      }
      if (available.contains(LeisureActivity.STAY_HOME)) {
         sb.append("  - stay_home: head straight home and turn in early\n");
      }
      sb.append("Respond with JSON only.");
      return sb.toString();
   }

   private static List<String> recentLeisureMemories(LlmVillagerComponent comp, int n) {
      List<String> out = new ArrayList<>();
      // Walk memories newest-first; embedded entries are append-only so
      // iterate the tail. EmbeddedEntry has a 'kind' field — tag we use
      // for leisure memories is 'leisure'.
      List<EmbeddedEntry> memories = comp.memories();
      for (int i = memories.size() - 1; i >= 0 && out.size() < n; i--) {
         EmbeddedEntry e = memories.get(i);
         if (e.kind() != null && "leisure".equals(e.kind())) {
            out.add(e.text());
         }
      }
      return out;
   }

   // ────────── Response parsing ──────────

   private static Result parseResult(LlmClient.LlmResult raw,
                                      LeisureActivity fallback,
                                      Set<LeisureActivity> available,
                                      String villagerName) {
      if (!raw.ok() || raw.content() == null || raw.content().isBlank()) {
         Townfolk.LOGGER.debug("Leisure LLM failed for {}: {}", villagerName, raw.error());
         return new Result(fallback, "Just felt like it.", false);
      }
      String content = raw.content().trim();
      // Strip code fences if the model added them despite instructions.
      if (content.startsWith("```")) {
         int firstNl = content.indexOf('\n');
         if (firstNl > 0) content = content.substring(firstNl + 1);
         if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
         content = content.trim();
      }
      try {
         JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
         String choiceStr = obj.has("choice") ? obj.get("choice").getAsString() : null;
         String reasoning = obj.has("reasoning") ? obj.get("reasoning").getAsString() : "";
         if (choiceStr == null) return new Result(fallback, "Just felt like it.", false);
         LeisureActivity picked = switch (choiceStr.toLowerCase(Locale.ROOT).trim()) {
            case "tavern"     -> LeisureActivity.TAVERN;
            case "walk"       -> LeisureActivity.WALK;
            case "stay_home", "stay home", "home" -> LeisureActivity.STAY_HOME;
            default            -> null;
         };
         if (picked == null || !available.contains(picked)) {
            Townfolk.LOGGER.debug("Leisure LLM returned unavailable choice {} for {}, falling back",
               choiceStr, villagerName);
            return new Result(fallback, "Just felt like it.", false);
         }
         if (reasoning == null || reasoning.isBlank()) reasoning = "Felt right.";
         if (reasoning.length() > 240) reasoning = reasoning.substring(0, 240);
         return new Result(picked, reasoning, true);
      } catch (Throwable t) {
         Townfolk.LOGGER.debug("Leisure LLM JSON parse failed for {}: {}",
            villagerName, t.getMessage());
         return new Result(fallback, "Just felt like it.", false);
      }
   }

   /** LLM-or-heuristic decision result. {@code fromLlm} differentiates
    *  in logs so we can see how often the offline path is being used. */
   public record Result(LeisureActivity activity, String reasoning, boolean fromLlm) {}
}

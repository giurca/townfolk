package com.yucareux.townfolk.world.leisure;

import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.biome.Biome;

/**
 * Heuristic bias evaluator for leisure-activity selection. Computes a
 * soft score per {@link LeisureActivity}, plus a list of narrated
 * "nudge" sentences suitable for both the offline weighted-random
 * fallback AND the LLM prompt context (Stage 11c).
 *
 * <p>Design tenet: scores are SOFT and NARRATED, never absolute.
 * The LLM (or random selector) sees "you seem tired tonight" and
 * a per-activity weight; it can still pick anything. Hard rules
 * — like "no tavern when there's no tavern" — gate the option list
 * BEFORE the evaluator runs.
 *
 * <p>Bias factors at v1:
 * <ul>
 *   <li><b>Tiredness</b> — health below max → bias to STAY_HOME
 *   <li><b>Pattern fatigue</b> — same choice 3+ evenings → bias against
 *   <li><b>Weather</b> — raining/thundering → bias against WALK
 *   <li><b>Crowding</b> — many villagers already at tavern → mild
 *       bias against TAVERN (still pickable)
 *   <li><b>Personality lean</b> — backstory keywords (drinks, solitary,
 *       wanders) → persistent bias
 *   <li><b>Random nudge</b> — every choice gets a small jitter so a
 *       villager who's chosen the same thing 3 nights running has
 *       a real chance to break the pattern
 * </ul>
 */
public final class BiasEvaluator {

   private BiasEvaluator() {}

   /** Bundle returned from {@link #evaluate}: per-activity scores
    *  (sum of biases, higher = more likely) plus the narrated nudge
    *  sentences (one per active bias). */
   public record Biases(
      Map<LeisureActivity, Double> scores,
      List<String> narration
   ) {}

   public static Biases evaluate(ServerLevel level,
                                  Villager v,
                                  LlmVillagerComponent comp,
                                  int tavernOccupants,
                                  boolean tavernAvailable) {
      EnumMap<LeisureActivity, Double> scores = new EnumMap<>(LeisureActivity.class);
      // Base weights — slightly favor TAVERN as the most flavorful option.
      scores.put(LeisureActivity.TAVERN,    tavernAvailable ? 1.2 : 0.0);
      scores.put(LeisureActivity.WALK,      1.0);
      scores.put(LeisureActivity.STAY_HOME, 0.8);
      List<String> narration = new ArrayList<>();

      // ── Tiredness — health proxy. < 80% pushes toward staying home. ──
      float hpFrac = v.getHealth() / Math.max(1.0f, v.getMaxHealth());
      if (hpFrac < 0.8f) {
         scores.merge(LeisureActivity.STAY_HOME, 0.5, Double::sum);
         scores.merge(LeisureActivity.WALK,    -0.2, Double::sum);
         narration.add("You've been on your feet all day.");
      }

      // ── Pattern fatigue — surface recent memories tagged 'leisure'. ──
      // If the last 3 leisure memories all mention the same destination,
      // discount that destination so the villager has a real chance to
      // break the rut. We can't introspect EmbeddedEntry tags cheaply
      // here without a search; settle for the cheapest signal — recent
      // dialogue / pinned-fact mentions of the place. Good enough for
      // v1; Stage 11c's prompt context will do the proper memory pass.
      // (Placeholder: no-op for now. Stage 11c will pass last-N choices
      //  in directly and apply this bias.)

      // ── Weather — raining? walking outdoors loses some appeal. ──
      if (level.isRaining()) {
         scores.merge(LeisureActivity.WALK, -0.5, Double::sum);
         scores.merge(LeisureActivity.TAVERN, 0.3, Double::sum);
         narration.add(level.isThundering()
            ? "A storm is rolling in."
            : "It's raining outside.");
      }

      // ── Crowding — soft bias only, hint not a cap. ──
      if (tavernOccupants >= 3) {
         scores.merge(LeisureActivity.TAVERN, -0.3, Double::sum);
         narration.add("The tavern sounds busy tonight.");
      } else if (tavernAvailable && tavernOccupants > 0) {
         scores.merge(LeisureActivity.TAVERN, 0.2, Double::sum);
         narration.add("There's already a few folk at the tavern.");
      }

      // ── Personality keywords pulled from backstory. ──
      String back = comp.backstory() == null ? "" : comp.backstory().toLowerCase();
      if (back.contains("drink") || back.contains("tavern") || back.contains("ale")) {
         scores.merge(LeisureActivity.TAVERN, 0.4, Double::sum);
         narration.add("You always did love a drink after work.");
      }
      if (back.contains("solitary") || back.contains("quiet") || back.contains("recluse")) {
         scores.merge(LeisureActivity.STAY_HOME, 0.4, Double::sum);
         scores.merge(LeisureActivity.TAVERN,   -0.2, Double::sum);
         narration.add("Crowds aren't really your thing.");
      }
      if (back.contains("wander") || back.contains("traveller") || back.contains("traveler")) {
         scores.merge(LeisureActivity.WALK, 0.4, Double::sum);
         narration.add("Your feet always itch by sundown.");
      }

      // ── Random nudge — small jitter to break ruts. ──
      double jitter = level.getRandom().nextDouble() * 0.3;
      LeisureActivity[] all = LeisureActivity.values();
      LeisureActivity surprise = all[level.getRandom().nextInt(all.length)];
      scores.merge(surprise, jitter, Double::sum);

      // ── Biome flavour (cosmetic narration only, no score change). ──
      try {
         var biome = level.getBiome(v.blockPosition());
         @SuppressWarnings("unused") Biome bb = biome.value();   // touch — future biome-specific hints
      } catch (Throwable ignored) {}

      // Clamp negative scores at 0.05 so a heavily-biased-against option
      // still has a tiny chance — preserves agency.
      scores.replaceAll((k, vScore) -> Math.max(0.05, vScore));

      return new Biases(scores, narration);
   }
}

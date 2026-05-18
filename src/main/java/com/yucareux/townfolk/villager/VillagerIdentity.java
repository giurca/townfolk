package com.yucareux.townfolk.villager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.VillagerProfession;

/**
 * "Who is this villager, by what they've been doing?"
 *
 * Tallies recent work-tagged memories into action counts, then derives:
 *   1. A vanilla {@link VillagerProfession} for visual / cosmetic re-skin
 *      (called at day rollover from NightlyCompactor).
 *   2. A human-readable reputation phrase ("farmer who also keeps sheep")
 *      surfaced in {@link com.yucareux.townfolk.world.WorldSense} for the
 *      LLM to weave into dialogue.
 *
 * Memory text is searched for verb keywords — the same words our routines
 * write into their memory entries ("harvested," "sheared," "tilled," etc.).
 * So identity drifts NATURALLY with what the villager has actually done.
 * A villager who pivots from farming to shepherding will gradually look
 * AND read as a shepherd, without any code path declaring them one.
 *
 * Hysteresis: the re-skin only fires when the dominant activity has at
 * least {@link #MIN_VOTES_FOR_RESKIN} entries, so brand-new villagers don't
 * flip identity every time they swing a hoe once.
 */
public final class VillagerIdentity {

   /** Look back this many in-game days for activity tallies. Long enough to
    *  smooth out a single day's anomaly; short enough to let identity drift. */
   public static final int LOOKBACK_DAYS = 7;

   /** Don't promote a vanilla profession until at least this many votes. */
   public static final int MIN_VOTES_FOR_RESKIN = 5;

   /** Sort actions into one of these buckets, then count per bucket. The
    *  bucket → vanilla profession mapping is below. */
   public static final String B_FARM     = "farm";       // harvest, plant, till, water, bake
   public static final String B_HERDER   = "herder";     // shear, milk, feed, breed (any livestock)
   public static final String B_BUTCHER  = "butcher";    // slaughtered, beef, mutton, porkchop, chicken, rabbit
   public static final String B_MINE     = "mine";       // mine, chop (no woodcutter — closest visual is mason)
   public static final String B_OTHER    = "other";

   /** Legacy alias for callers still referencing the old "shepherd" bucket.
    *  Points at the broader herder bucket so old reads keep working. */
   @Deprecated public static final String B_SHEPHERD = B_HERDER;

   /** Walk the villager's work-tagged memories within the lookback window,
    *  bucketing by the verb keyword and returning per-bucket counts. */
   public static Map<String, Integer> tally(LlmVillagerComponent comp, long currentDay) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      long cutoffDay = currentDay - LOOKBACK_DAYS;
      for (EmbeddedEntry m : comp.memories()) {
         if (m.createdDay() < cutoffDay) continue;
         if (!"work".equals(m.kind())) continue;
         String text = m.text().toLowerCase(Locale.ROOT);
         String bucket = bucketFor(text);
         counts.merge(bucket, 1, Integer::sum);
      }
      return counts;
   }

   private static String bucketFor(String lowerCaseText) {
      if (lowerCaseText.contains("harvest") || lowerCaseText.contains("plant")
       || lowerCaseText.contains("tilled") || lowerCaseText.contains("water")
       || lowerCaseText.contains("baked")  || lowerCaseText.contains("bread")) {
         return B_FARM;
      }
      // Butchering reads as its own identity — slaughter/raw-meat language
      // sits in a different bucket from raising/tending. Check before the
      // generic herder bucket so a memory of "I slaughtered a cow" doesn't
      // get caught by "cow".
      if (lowerCaseText.contains("slaughter") || lowerCaseText.contains("butcher")
       || lowerCaseText.contains("raw beef")  || lowerCaseText.contains("raw porkchop")
       || lowerCaseText.contains("raw chicken") || lowerCaseText.contains("raw rabbit")
       || lowerCaseText.contains("raw mutton") || lowerCaseText.contains("leather")) {
         return B_BUTCHER;
      }
      // Herder: any sign of tending livestock — shear, milk, feed, breed,
      // egg-collecting, naming the species. Catches sheep / cow / pig /
      // chicken / rabbit / mooshroom / llama / goat memories generically.
      if (lowerCaseText.contains("shear")   || lowerCaseText.contains("wool")
       || lowerCaseText.contains("milk")    || lowerCaseText.contains("milked")
       || lowerCaseText.contains("feed")    || lowerCaseText.contains("fed")
       || lowerCaseText.contains("breed")   || lowerCaseText.contains("bred")
       || lowerCaseText.contains("hatched") || lowerCaseText.contains("egg")
       || lowerCaseText.contains("cow")     || lowerCaseText.contains("pig")
       || lowerCaseText.contains("sheep")   || lowerCaseText.contains("chicken")
       || lowerCaseText.contains("rabbit")) {
         return B_HERDER;
      }
      if (lowerCaseText.contains("mine") || lowerCaseText.contains("chop")
       || lowerCaseText.contains("logs") || lowerCaseText.contains("stone")) {
         return B_MINE;
      }
      return B_OTHER;
   }

   /** Pick the dominant vanilla {@link Holder<VillagerProfession>} for cosmetic
    *  re-skin, or {@code null} if no bucket has reached the vote threshold. */
   public static Holder<VillagerProfession> dominantProfession(Map<String, Integer> tally) {
      String top = null;
      int topCount = 0;
      for (var e : tally.entrySet()) {
         if (e.getValue() > topCount) { topCount = e.getValue(); top = e.getKey(); }
      }
      if (top == null || topCount < MIN_VOTES_FOR_RESKIN) return null;
      String professionPath = switch (top) {
         case B_FARM    -> "farmer";
         case B_HERDER  -> "shepherd";
         case B_BUTCHER -> "butcher";
         case B_MINE    -> "mason";
         default -> null;
      };
      if (professionPath == null) return null;
      var profKey = net.minecraft.resources.ResourceKey.create(
         net.minecraft.core.registries.Registries.VILLAGER_PROFESSION,
         net.minecraft.resources.ResourceLocation.parse("minecraft:" + professionPath));
      return BuiltInRegistries.VILLAGER_PROFESSION.getHolder(profKey).orElse(null);
   }

   /** Human-readable identity phrase. Compositional: a villager with non-
    *  trivial counts in both farming and shepherding becomes "a farmer who
    *  also keeps sheep." */
   public static String reputation(Map<String, Integer> tally) {
      int farm = tally.getOrDefault(B_FARM, 0);
      int herd = tally.getOrDefault(B_HERDER, 0);
      int butc = tally.getOrDefault(B_BUTCHER, 0);
      int mine = tally.getOrDefault(B_MINE, 0);
      int total = farm + herd + butc + mine + tally.getOrDefault(B_OTHER, 0);
      if (total < MIN_VOTES_FOR_RESKIN) return "";   // no identity yet

      // Primary = most votes. Secondary = next, if it has ≥ 1/3 of the primary.
      int[] cnts   = { farm,     herd,             butc,      mine };
      String[] lbls = { "farmer", "livestock-keeper", "butcher", "miner/stoneworker" };
      int primary = -1, secondary = -1;
      for (int i = 0; i < cnts.length; i++) {
         if (primary < 0 || cnts[i] > cnts[primary]) {
            secondary = primary; primary = i;
         } else if (secondary < 0 || cnts[i] > cnts[secondary]) {
            secondary = i;
         }
      }
      if (primary < 0 || cnts[primary] == 0) return "";
      String head = "a " + lbls[primary];
      if (secondary >= 0 && cnts[secondary] > 0
          && cnts[secondary] >= Math.max(1, cnts[primary] / 3)) {
         String s = lbls[secondary];
         head += " who also " + switch (s) {
            case "livestock-keeper" -> "raises animals";
            case "farmer"           -> "tends a field";
            case "butcher"          -> "works meat";
            case "miner/stoneworker"-> "works the stone";
            default -> s;
         };
      }
      return head;
   }

   private VillagerIdentity() {}
}

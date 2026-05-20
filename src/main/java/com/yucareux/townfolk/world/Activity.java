package com.yucareux.townfolk.world;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-villager schedule + leisure activity state. The single source of
 * truth for what a townsfolk is "doing right now" — replaces the raw
 * string literals (e.g. {@code "going_to_work"}) that were duplicated
 * across {@link ScheduleService}, {@link com.yucareux.townfolk.client.screen.TownAdminScreen},
 * {@link com.yucareux.townfolk.world.WorldSense}, {@link com.yucareux.townfolk.town.TownRegistry},
 * the Villagers grid filter, and the admin dialogue tallies.
 *
 * <p>Three rendering modes share the same enum value:
 * <ul>
 *   <li>{@link #wireKey()} — lowercase snake-case for the
 *       network payload + persistent memory. Unchanged across the
 *       Stage 16a migration so existing wire format + saved data stays
 *       compatible. Also what {@link #fromWire(String)} round-trips.
 *   <li>{@link #prettyOverhead()} — the floating "(at work)" label
 *       rendered above the villager's head by {@code LlmTownsfolk}.
 *   <li>{@link #displayLabel()} — the admin UI's short form
 *       ("heading to work") used by the villager-detail header pill
 *       and the Villagers-tab grid tiles.
 *   <li>{@link #llmPhrase()} — the first-person English phrase fed
 *       into the LLM system prompt by {@code WorldSense}
 *       ("at my workstation, working"). Tweak here, not in 12 switches.
 * </ul>
 *
 * <p>Conventions match {@link com.yucareux.townfolk.villager.ProfessionTraits}
 * (Stage 15c): {@link #fromWire(String)} returns {@link #IDLE} for
 * unknown / null keys so call sites never need a null check.
 */
public enum Activity {

   // Schedule-driven (ScheduleService sets these based on the day-time phase).
   WAKING        ("waking",         "(waking up)",     "waking up",       "just waking up"),
   GOING_TO_WORK ("going_to_work",  "(off to work)",   "heading to work", "walking to my workstation"),
   AT_WORK       ("at_work",        "(at work)",       "at work",         "at my workstation, working"),
   GOING_HOME    ("going_home",     "(heading home)",  "heading home",    "heading home"),
   AT_HOME       ("at_home",        "(at home)",       "at home",         "at home, settling in"),
   SLEEPING      ("sleeping",       "(sleeping)",      "sleeping",        "trying to get to bed"),

   // Player-issued (FollowService).
   FOLLOWING     ("following",      "(following)",     "following",       "following the player"),

   // Leisure-driven (LeisureService overrides during the evening window).
   AT_TAVERN     ("at_tavern",      "(at the tavern)", "at the tavern",   "at the tavern, relaxing"),
   WANDERING     ("wandering",      "(wandering)",     "wandering",       "wandering around town"),

   // Default / fallback — nothing scheduled, no leisure plan, no follow.
   IDLE          ("idle",           "",                "idle",            "standing idle");

   private final String wireKey;
   private final String prettyOverhead;
   private final String displayLabel;
   private final String llmPhrase;

   Activity(String wireKey, String prettyOverhead, String displayLabel, String llmPhrase) {
      this.wireKey        = wireKey;
      this.prettyOverhead = prettyOverhead;
      this.displayLabel   = displayLabel;
      this.llmPhrase      = llmPhrase;
   }

   public String wireKey()        { return wireKey; }
   public String prettyOverhead() { return prettyOverhead; }
   public String displayLabel()   { return displayLabel; }
   public String llmPhrase()      { return llmPhrase; }

   /** True for AT_WORK / GOING_TO_WORK — the two "engaged with the
    *  workstation" states. Drives the admin-overview "working" counter
    *  and the Villagers-tab filter chip. */
   public boolean isWorking()  { return this == AT_WORK || this == GOING_TO_WORK; }

   /** True for AT_HOME / GOING_HOME — the home half of the evening
    *  schedule. Sleep is a separate bucket. */
   public boolean isAtHome()   { return this == AT_HOME || this == GOING_HOME; }

   /** True only for SLEEPING. Kept as its own predicate because the
    *  admin overview treats sleep as a distinct (third) bucket. */
   public boolean isSleeping() { return this == SLEEPING; }

   /** True only for IDLE — used by autonomy to detect "trivially
    *  idle" responses worth skipping cooldown for. */
   public boolean isIdle()     { return this == IDLE; }

   private static final Map<String, Activity> BY_WIRE = new HashMap<>();
   static {
      for (Activity a : values()) BY_WIRE.put(a.wireKey, a);
   }

   /** Parse the wire key. Returns {@link #IDLE} for {@code null} /
    *  unknown so callers can chain without null checks. */
   public static Activity fromWire(String key) {
      if (key == null) return IDLE;
      Activity a = BY_WIRE.get(key);
      return a == null ? IDLE : a;
   }
}

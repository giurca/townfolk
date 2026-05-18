package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A persistent "standing order" attached to a villager — a deterministic
 * (trigger, action) pair the autopilot fires whenever the trigger matches.
 *
 * The villager's identity, memory, and todos make them *aware* of past
 * promises. Reflexes make the autopilot *act* on them without LLM
 * mediation, so a player's "from now on, do X" instruction translates into
 * actual behaviour the moment the trigger condition arises.
 *
 * Examples:
 *   Reflex("r1", "after:harvest",        "deposit all wheat",        ...)
 *   Reflex("r2", "inv:>=:cobblestone:32","deposit all cobblestone",   ...)
 *   Reflex("r3", "phase:dawn",            "harvest",                  ...)
 *   Reflex("r4", "after:chop",            "place oak_sapling",        ...)   ← lumberjack
 *
 * Trigger syntax (namespaced, extensible — add new families without
 * touching old reflexes):
 *
 *   after:<verb>                ← fires when villager just finished <verb>
 *                                 (harvest, plant, chop, mine, place, deposit,
 *                                 withdraw, peek, craft, hand, eat, give, …)
 *   inv:>=:<item>:<n>           ← fires while villager has ≥ n of <item>
 *   inv:<=:<item>:<n>           ← fires while villager has ≤ n of <item>
 *   phase:<phase>               ← fires on entering <phase> (dawn, work_hours,
 *                                 evening, dusk, night)
 *
 * Future:
 *   near:<entity-or-block>:<r>  ← spatial
 *   health:<n>                  ← state
 *   weather:<kind>              ← environmental
 *   time_of_day:<bucket>        ← fine-grained
 *
 * Action is just a verb body, dispatched through the existing
 * {@code ToolDispatcher.execute}. So a reflex can do anything an LLM can
 * emit via [ACTION: …]. No reflex-specific verb set.
 *
 * Lifecycle:
 *   - Created via LLM marker, post-dialogue extractor, or admin UI.
 *   - Fires when trigger matches AND {@link #COOLDOWN_TICKS} has elapsed
 *     since the last fire (prevents level-triggered reflexes from
 *     thrashing every tick).
 *   - Removed via [ACTION: forget …] or admin UI.
 */
public record Reflex(
   String id,
   String trigger,
   String action,
   long createdDay,
   long lastFiredTick
) {

   /** Per-reflex cooldown so a "while inv ≥ N" rule doesn't fire every check
    *  tick. Edge-triggered rules (after:, phase:) effectively cool down on
    *  the natural cadence of the trigger anyway, but the same constant
    *  applies uniformly for predictability. */
   public static final long COOLDOWN_TICKS = 20L * 10;     // 10 seconds

   /** Sentinel for "never fired". Real game ticks are always non-negative
    *  and start at 0, so any positive {@code lastFiredTick} represents a
    *  real fire. Using {@code 0L} (rather than {@code Long.MIN_VALUE}) lets
    *  {@code now - lastFiredTick} stay in safe arithmetic range. */
   public static final long NEVER_FIRED = 0L;

   public static final Codec<Reflex> CODEC = RecordCodecBuilder.create(i -> i.group(
      Codec.STRING.fieldOf("id").forGetter(Reflex::id),
      Codec.STRING.fieldOf("trigger").forGetter(Reflex::trigger),
      Codec.STRING.fieldOf("action").forGetter(Reflex::action),
      Codec.LONG.optionalFieldOf("createdDay", 0L).forGetter(Reflex::createdDay),
      Codec.LONG.optionalFieldOf("lastFiredTick", NEVER_FIRED).forGetter(Reflex::lastFiredTick)
   ).apply(i, Reflex::new));

   public Reflex withLastFired(long tick) {
      return new Reflex(id, trigger, action, createdDay, tick);
   }
}

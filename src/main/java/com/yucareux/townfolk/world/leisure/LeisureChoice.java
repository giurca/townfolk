package com.yucareux.townfolk.world.leisure;

import net.minecraft.core.BlockPos;

/**
 * A villager's leisure decision for a given day. Held in-memory only
 * by {@link LeisureService} — not persisted to disk. Server restart
 * mid-evening re-rolls the choice next time the leisure window opens.
 *
 * <p>The {@link #targetPos} is the resolved destination at decision
 * time (tavern interior centre for TAVERN, current wander target for
 * WALK, null for STAY_HOME). Re-rolled for WALK every wander
 * sub-cycle by the executor.
 *
 * @param day       gametime / 24000 — clears on day rollover
 * @param activity  what the villager chose
 * @param reasoning brief one-liner — surfaced to TownLog + memory
 * @param targetPos current destination (may be reassigned over the
 *                  evening for WALK), or null when no destination is
 *                  relevant
 */
public record LeisureChoice(
   long day,
   LeisureActivity activity,
   String reasoning,
   BlockPos targetPos
) {
   /** Build a no-destination choice — STAY_HOME, or a TAVERN/WALK
    *  choice whose target hasn't been resolved yet. */
   public static LeisureChoice of(long day, LeisureActivity activity, String reasoning) {
      return new LeisureChoice(day, activity, reasoning, null);
   }

   public LeisureChoice withTarget(BlockPos pos) {
      return new LeisureChoice(day, activity, reasoning, pos);
   }
}

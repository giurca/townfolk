package com.yucareux.townfolk.world.leisure;

/**
 * What a villager has chosen to do during the leisure window —
 * the gametime band between work_hours ending and dusk (≈ gametime
 * 9000-12000). Picked once per evening by {@link LeisureService},
 * driven for v1 by heuristic-weighted random and in Stage 11c by
 * the LLM via the {@code choose_evening_activity} tool.
 *
 * <p>Three options at launch. The enum is the extension point —
 * future activities (VISIT_FRIEND, GO_FISHING, ATTEND_MARKET, ...)
 * just add an entry and an executor branch in
 * {@link LeisureService}.
 */
public enum LeisureActivity {
   /** Walk to an active recognized tavern building, idle there
    *  until dusk, then head home for bed. */
   TAVERN,
   /** Wander randomly within the owning town's coverage area,
    *  picking new short-range targets every ~20 seconds.
    *  At dusk, head home. */
   WALK,
   /** Skip leisure — head straight home / stand by the bed.
    *  Functionally identical to the pre-Stage-11 behavior, but
    *  recorded as an explicit choice so memory accumulates and
    *  the LLM sees pattern-of-life data. */
   STAY_HOME;

   /** Short human-readable name used in chat + TownLog entries
    *  + memory text. Keep it player-facing. */
   public String label() {
      return switch (this) {
         case TAVERN    -> "the tavern";
         case WALK      -> "a walk";
         case STAY_HOME -> "home";
      };
   }
}

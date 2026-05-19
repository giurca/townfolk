package com.yucareux.townfolk.building;

import net.minecraft.server.level.ServerLevel;

/**
 * Resolves a town's effective population cap from the level's
 * {@link BuildingRegistry}.
 *
 * <p>Formula (Stage 10a):
 * <pre>
 *   cap = #active Home buildings + civicBonus
 *   civicBonus = 4 if any active Town Hall exists, else 0
 * </pre>
 *
 * <p>Both home count and town hall recognition are LEVEL-scoped, not
 * town-scoped. In a typical single-town world this is identical to
 * town-scoped; in a multi-town world we'd want to scope it by
 * {@code TownCoverage.contains(building.markerPos())}. That refinement
 * is deferred until we actually support multi-town worlds in a way
 * that needs it.
 *
 * <p>Cap = 0 is a valid state (no homes, no town hall) — the town
 * literally can't house anyone. Player must build at least one home
 * to recruit their first villager.
 */
public final class PopulationCap {

   /** +4 cap when a Town Hall is recognized. Small enough that the
    *  player can't skip Home buildings; large enough that it's worth
    *  enclosing the Charter Stone. */
   public static final int TOWN_HALL_BONUS = 4;

   private PopulationCap() {}

   /** Number of valid (active) Home buildings in the level. */
   public static int homeCount(ServerLevel level) {
      return BuildingRegistry.countActiveOfType(level, BuildingTemplates.HOME);
   }

   /** True iff at least one Town Hall is recognized in the level. */
   public static boolean hasTownHall(ServerLevel level) {
      return BuildingRegistry.countActiveOfType(level, BuildingTemplates.TOWN_HALL) > 0;
   }

   /** Effective population cap for the level. */
   public static int effectiveCap(ServerLevel level) {
      int homes = homeCount(level);
      int civic = hasTownHall(level) ? TOWN_HALL_BONUS : 0;
      return homes + civic;
   }

   /** Compact summary for chat / UI: "3 homes + town hall = 7" or
    *  "5 homes (no town hall)". */
   public static String describe(ServerLevel level) {
      int homes = homeCount(level);
      boolean th = hasTownHall(level);
      if (th) return homes + " home" + (homes == 1 ? "" : "s")
                  + " + town hall = " + (homes + TOWN_HALL_BONUS);
      return homes + " home" + (homes == 1 ? "" : "s") + " (no town hall)";
   }
}

package com.yucareux.townfolk.world.livestock;

import java.util.List;

/**
 * Registry of every {@link LivestockTask} the parcel routine considers
 * on an ANIMAL parcel. Ordering = priority.
 *
 * <p><b>Priority rationale:</b> BREED tasks run FIRST. The player's
 * {@code AnimalPlan.BREED_UP} signal is explicit ("grow my herd"), so
 * when its gate is satisfied (population below target + food + adult
 * pair nearby) breeding wins over the milk/shear harvest tasks for
 * that pass. As soon as population hits target, the gate flips and
 * the parcel routine falls through to HARVEST naturally.
 *
 * <p>This is what lets a player run "cap milk at 4, target cows at 12"
 * without one starving out the other — the two systems track
 * different goals (production-cap for output stockpile, AnimalPlan
 * for population) and the priority order makes BREED dominant only
 * while there's actual breeding work to do.
 *
 * <p>To add a new species or interaction:
 *   1. Implement {@link LivestockTask} with the new logic.
 *   2. Append it here in priority order (breed tasks at the top, harvest below).
 *   3. (If it needs a tool the treasury can fetch) the ParcelRoutine's
 *      work-need scan will already see it via {@link LivestockTask#toolItemId()}
 *      / {@link LivestockTask#toolTag()}.
 *
 * <p>Everything else — find target on parcel, walk + perform, log the
 * memory, chain-fire next tick — is wired up through {@link LivestockTask}
 * directly.
 */
public final class LivestockTasks {

   public static final LivestockTask SHEAR_SHEEP = new ShearSheepTask();
   public static final LivestockTask MILK_COW    = new MilkCowTask();

   public static final LivestockTask BREED_SHEEP   = BreedTask.forSheep();
   public static final LivestockTask BREED_COW     = BreedTask.forCow();
   public static final LivestockTask BREED_PIG     = BreedTask.forPig();
   public static final LivestockTask BREED_CHICKEN = BreedTask.forChicken();
   public static final LivestockTask BREED_RABBIT  = BreedTask.forRabbit();

   /** Iteration order = priority. Breed first (only fires when the
    *  AnimalPlan.BREED_UP gate is satisfied — see
    *  {@code ParcelRoutine.breedAllowedFor}), then harvest. */
   public static final List<LivestockTask> ALL = List.of(
      BREED_SHEEP,
      BREED_COW,
      BREED_PIG,
      BREED_CHICKEN,
      BREED_RABBIT,
      SHEAR_SHEEP,
      MILK_COW
   );

   /** Tasks the WorkNeed fetch-loop scans for. Breed tasks are excluded
    *  because their tool (the breeding food) is part of the harvest the
    *  villager is already managing — we don't want a chicken farmer
    *  abandoning their parcel to fetch wheat seeds purely to breed. The
    *  food shows up via the normal seed-fetch / harvest cycle, and
    *  breed simply waits its turn. */
   public static final List<LivestockTask> TOOL_FETCH = List.of(
      SHEAR_SHEEP,
      MILK_COW
   );

   private LivestockTasks() {}
}

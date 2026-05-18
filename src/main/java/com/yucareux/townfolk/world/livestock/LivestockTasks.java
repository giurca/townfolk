package com.yucareux.townfolk.world.livestock;

import java.util.List;

/**
 * Registry of every {@link LivestockTask} the parcel routine considers
 * on an ANIMAL parcel. Ordering = priority. Time-sensitive harvest-style
 * tasks (shear, milk) come first — they yield consumables that fill up
 * the bag and trigger deposits, so we'd rather knock them out than
 * stand around breeding. Breeding tasks come last because they're
 * housekeeping: a sheep parcel that just got sheared can profitably
 * spend the next tick pairing up.
 *
 * To add a new species or interaction:
 *   1. Implement {@link LivestockTask} with the new logic.
 *   2. Append it here in priority order.
 *   3. (If it needs a tool the treasury can fetch) the ParcelRoutine's
 *      work-need scan will already see it via {@link LivestockTask#toolItemId()}
 *      / {@link LivestockTask#toolTag()}.
 *
 * Everything else — find target on parcel, walk + perform, log the
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

   /** Iteration order = priority. Harvest tasks first, then breeding. */
   public static final List<LivestockTask> ALL = List.of(
      SHEAR_SHEEP,
      MILK_COW,
      BREED_SHEEP,
      BREED_COW,
      BREED_PIG,
      BREED_CHICKEN,
      BREED_RABBIT
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

package com.yucareux.townfolk.world.parcel.tasks;

import com.yucareux.townfolk.world.WorkProductionService.Ctx;
import com.yucareux.townfolk.villager.FieldRegion;
import java.util.List;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Stage 24a — interface that the long-deferred 17b refactor settled on.
 *
 * <p>A {@code ParcelTask} is an evaluator the parcel-routine asks "are
 * you currently relevant for this villager × this parcel?" The
 * answer is an integer score; positive means "yes, with this much
 * priority"; zero means "not now"; negative is reserved for future
 * "actively-rejected" reasons.
 *
 * <p>The current {@code ParcelRoutine} dispatches via a long inline
 * cascade inside {@code tryAllForPlantParcel} / {@code tryAllForAnimalParcel}.
 * Future migration: each branch becomes a {@code ParcelTask}
 * implementation; the routine reduces to "iterate registered tasks,
 * pick highest score, fire execute()". This stage ships the
 * interface + the first new (Create-flavoured) task so the migration
 * has a concrete pattern to follow without dropping a 2,000-line
 * refactor in one commit.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #id()} is a stable string used by reflex triggers
 *       ({@code after:parcel_task:operate_farm}) and the admin log.
 *   <li>{@link #requiredItems()} surfaces tag-driven item
 *       prerequisites so the missing-resource fall-through (currently
 *       {@code tryFetchMissingResource}) can satisfy a task before
 *       calling {@link #execute}.
 *   <li>{@link #score} must be cheap — it's called per parcel per
 *       tick. Anything block-scan-flavoured should peek a cached
 *       summary, not walk the whole parcel.
 *   <li>{@link #execute} is allowed to enqueue block / entity tasks
 *       via {@code BlockTaskQueue} / {@code EntityTaskQueue}; it's
 *       called at most once per villager per tick.
 * </ul>
 */
public interface ParcelTask {

   /** Score + cached payload from a single {@link #evaluate} call.
    *  Threading the payload through avoids the score→execute double-
    *  scan that audit P1-A flagged: implementations can do the
    *  expensive lookup (block scan, recipe match) once, stash the
    *  result here, and read it back in {@link #execute}.
    *
    *  <p>{@code payload} is opaque — each task's evaluate + execute
    *  pair agree on the type. Use {@code null} when no payload is
    *  needed. */
   record Eval(int score, Object payload) {
      public static final Eval NONE = new Eval(0, null);
   }

   /** Stable id used by reflexes + diagnostic logs. Lowercase
    *  snake_case by convention ("harvest_ripe", "operate_farm"). */
   String id();

   /** Score + payload for this (villager, parcel) pair. {@code score <= 0}
    *  means "not relevant right now"; higher = more pressing. Compared
    *  across all registered tasks per scan. The dispatcher only
    *  invokes {@link #execute} on the winning task and passes the
    *  payload from this method's result. */
   Eval evaluate(Ctx ctx, FieldRegion parcel);

   /** Fire the task. {@code payload} is the value returned by the
    *  winning {@link #evaluate} call. Returns true iff some work was
    *  actually dispatched. */
   boolean execute(Ctx ctx, FieldRegion parcel, Object payload);

   /** Item tags this task wants the villager to be carrying — used
    *  by the upstream "missing resource" fall-through to fetch
    *  ingredients before {@link #execute} fires. Empty list means
    *  "no requirements". */
   default List<TagKey<Item>> requiredItems() { return List.of(); }
}

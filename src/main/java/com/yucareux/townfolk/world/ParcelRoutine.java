package com.yucareux.townfolk.world;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.MemoryStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * The single, parcels-driven work routine that REPLACES the old
 * profession-keyed table. A villager has zero, one, or many
 * {@link FieldRegion}s. This routine scans each owned parcel each tick, scores
 * a list of candidate tasks against a priority table, and dispatches the
 * highest-priority feasible one through the existing verb system.
 *
 * Identity is not pre-bound. Whether a villager is a "wheat farmer," "wheat
 * farmer who also tends sheep," "lumberjack," or "nothing yet" is read off
 * what their parcels currently contain and what they're carrying. The
 * routine adapts. So does the nightly compaction and the prompt — the LLM
 * sees what they did, the player sees the cosmetic re-skin, and the
 * narrative emerges.
 *
 * Priority table (current, expandable):
 *   URGENT  unshorn sheep in parcel + shears          → shear
 *   URGENT  ripe crop in parcel                       → harvest (+ atomic replant if seed)
 *   HIGH    dry farmland in parcel                    → water
 *   HIGH    empty tilled farmland in parcel + seed    → plant
 *   LOW     non-farmable surface block (non-grazing)  → reclaim → dirt
 *   LOW     tillable grass/dirt + hoe + seed          → till
 *
 * Cross-parcel: at every tick we ALSO try to bank surplus (wheat ≥32,
 * seeds ≥96, …) and auto-fetch missing tools/seeds from the treasury.
 * Auto-bake / auto-eat are not autonomous — they're explicit LLM verbs.
 *
 * Villagers with no parcels do nothing autonomous (no profession fallback).
 * The player must assign land via the Surveyor's Stake to give them work.
 */
public final class ParcelRoutine {

   /** Try one tick. Returns true if the routine issued any task.
    *
    *  Behaviour rule: STICK ON THE ACTIVE PARCEL. Once the villager is
    *  physically inside one of their parcels, exhaust every actionable job
    *  on THAT parcel before considering any others. Without this, a
    *  villager arriving at a wheat field with 8 ripe stalks would harvest
    *  one, then re-scan all parcels and might wander off to water another
    *  field — finishing nothing. The "stick" rule keeps work coherent and
    *  matches how a human would actually farm a plot. */
   public static boolean tryTick(WorkProductionService.Ctx ctx) {
      if (ctx.comp().parcels().isEmpty()) return false;

      // Yield to the schedule outside work hours. Without this, the
      // chain listener fires a new task on every BlockTask completion
      // and the villager harvests / deposits straight through the
      // evening, dusk, and night phases — ignoring bedtime entirely.
      if (!com.yucareux.townfolk.world.ScheduleService.isWorkHours(ctx.level())) {
         VerboseLog.write("PARCEL_OFF_HOURS", "actor=" + ctx.entry().name(),
            "dayTime=" + (ctx.level().getDayTime() % 24000L));
         return false;
      }

      // First thing every tick: compact same-item stacks so 34 + 30
      // seeds become one stack of 64 in a single slot. Without this,
      // ItemPickupGoal grabs / sequential harvest drops / shear yields
      // accumulate in fresh slots and the bag "fills up" at half
      // capacity, never reaching the trigger threshold.
      compactInventory(ctx.actor());

      // Storage discovery is GONE in the registry model: villagers only
      // see containers the player has explicitly registered via the
      // sneak+empty-hand-right-click popup. See StorageRegistry.

      // Build a single inventory snapshot — totals per item id + free
      // slot count. Replaces what used to be three separate full-bag
      // walks per tryTick (pressure check, deposit rule iteration,
      // sweep "meaningful" check). Compact ran first, so the snapshot
      // reflects the de-duplicated slot layout.
      InventorySnapshot snap = snapshot(ctx.actor());

      // OPPORTUNISTIC SWEEP: if the villager happens to be at a barrel
      // RIGHT NOW (just arrived from any deposit, or working a parcel
      // that abuts one), dump everything not relevant to current
      // activity in a single batched action. Gated on "something has
      // crossed its trigger" so we don't fire every harvest just
      // because the parcel touches a barrel.
      sweepNonEssentialIfAtBarrel(ctx, snap);

      // Close any [need:X] todos whose underlying gap is now filled — keeps
      // the hail chat / open-commitments list honest the moment the player
      // hands the villager the missing tool/seeds.
      closeSatisfiedNeeds(ctx);

      boolean haveShears = snap.has("minecraft:shears");
      Item seed = findSeed(ctx.actor().getInventory());
      boolean haveHoe = hasToolWithTag(ctx.actor(), net.minecraft.tags.ItemTags.HOES);
      VerboseLog.write("PARCEL_TICK", "actor=" + ctx.entry().name()
         + " parcels=" + ctx.comp().parcels().size()
         + " haveShears=" + haveShears + " haveHoe=" + haveHoe
         + " haveSeed=" + (seed != null),
         "free=" + snap.freeSlots() + "/" + snap.totalSlots()
            + " items=" + snap.totals());

      // PRESSURE CHECK: if the villager's bag has < 1 free slot, drop
      // work and go bank BEFORE picking up the next harvest / shear /
      // reclaim. Otherwise items addItem-overflow and drop on the ground.
      if (snap.isPressured()) {
         VerboseLog.write("PARCEL_PRESSURE", "actor=" + ctx.entry().name(),
            "free=" + snap.freeSlots());
         if (tryDepositSurplus(ctx, snap, true)) return true;
         if (tryDumpMisc(ctx)) return true;
         VerboseLog.write("PARCEL_PRESSURE_STUCK", "actor=" + ctx.entry().name(),
            "no deposit route — falling through to normal work");
      }

      // NORMAL DEPOSIT (BEFORE parcel work). The old order was "parcel
      // work first, then deposit" — but parcel work essentially never
      // ends (something is always ripe / dry / tillable), so the deposit
      // step was unreachable in practice. Banking 32+ wheat or a 96+
      // seed-stack is part of the work cycle, not an afterthought.
      if (tryDepositSurplus(ctx, snap, false)) return true;

      // Order parcels so the one we're standing in is checked first; only
      // fall through to other parcels if the active one has nothing
      // actionable left. This implements the "stay until done" rule.
      java.util.List<FieldRegion> ordered = orderParcelsByActive(ctx);
      for (FieldRegion parcel : ordered) {
         if (tryAllForParcel(ctx, parcel, haveShears, seed, haveHoe)) return true;
      }

      // Nothing actionable. Before giving up, try to auto-fetch a missing
      // tool/seed from the town treasury — if a barrel anywhere in town has
      // what we need, walk over and grab it. This closes the loop the player
      // expected: "villager grabs shears from any barrel that contains one".
      if (tryFetchMissingResource(ctx, seed != null, haveHoe)) return true;

      // Treasury didn't have anything useful either. Write a one-per-day
      // "lacking X" memory + need-tagged todo so NeedsService hails the
      // player.
      writeLackingMemoryIfBlocked(ctx, seed != null, haveHoe);
      VerboseLog.write("PARCEL_IDLE", "actor=" + ctx.entry().name(),
         "no actionable work + no fetchable resource — wrote lacking memory");
      return false;
   }

   /** Walk the bag and merge same-item stacks into earlier slots. Pure
    *  housekeeping — leaves item totals unchanged, just frees slots so
    *  the routine sees the true free-slot count and the deposit
    *  thresholds reflect the actual carry weight. */
   private static void compactInventory(net.minecraft.world.entity.npc.Villager actor) {
      var inv = actor.getInventory();
      int size = inv.getContainerSize();
      for (int i = 0; i < size; i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) continue;
         int max = s.getMaxStackSize();
         if (s.getCount() >= max) continue;
         for (int j = i + 1; j < size; j++) {
            ItemStack t = inv.getItem(j);
            if (t.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(s, t)) continue;
            int room = max - s.getCount();
            int move = Math.min(room, t.getCount());
            s.grow(move);
            t.shrink(move);
            if (t.isEmpty()) inv.setItem(j, ItemStack.EMPTY);
            if (s.getCount() >= max) break;
         }
      }
   }


   /** Maximum time a villager will keep prioritising the parcel they're
    *  standing in before other parcels get a chance. 2 minutes is long
    *  enough to plough a small field but short enough that a giant plot
    *  with infinite work doesn't starve the others. */
   private static final long PARCEL_TIME_BUDGET_TICKS = 20L * 60 * 2;

   /** Per-villager record of which parcel they're currently working and
    *  when they entered it. Used by the parcel ordering to enforce the
    *  time budget — if they've been on the same parcel for >2 min, other
    *  parcels get sorted ahead of it on the next tick. The entry time
    *  resets whenever the villager physically leaves and re-enters. */
   private record ParcelVisit(String parcelId, long enteredAt) {}
   private static final java.util.Map<java.util.UUID, ParcelVisit> ACTIVE_VISITS =
      new java.util.concurrent.ConcurrentHashMap<>();

   /** Called by {@link com.yucareux.townfolk.event.EntityLifecycleHandler}
    *  when a villager is removed/unloaded — drops any per-villager state
    *  stashed in static maps to prevent slow leaks over a long session. */
   public static void onVillagerRemoved(java.util.UUID villagerId) {
      ACTIVE_VISITS.remove(villagerId);
      LACK_WRITES.remove(villagerId);
   }

   /** Sort the villager's parcels for this tick. Rules:
    *   1. The parcel containing the villager goes FIRST, so it gets
    *      exhausted before any sibling parcel is touched.
    *   2. EXCEPT: if the villager has been on that same parcel for longer
    *      than {@link #PARCEL_TIME_BUDGET_TICKS}, demote it to LAST so the
    *      other parcels are tried first. Once they're done (or the villager
    *      walks off and back on) the budget resets.
    *   3. If the villager isn't standing in any parcel, natural order. */
   private static java.util.List<FieldRegion> orderParcelsByActive(WorkProductionService.Ctx ctx) {
      BlockPos here = ctx.actor().blockPosition();
      var list = new java.util.ArrayList<>(ctx.comp().parcels());
      java.util.UUID id = ctx.actor().getUUID();
      long now = ctx.level().getGameTime();

      FieldRegion current = null;
      for (FieldRegion p : list) {
         if (p.contains(here)) { current = p; break; }
      }

      if (current == null) {
         // Outside any parcel — drop the timer so the next entry is a
         // fresh visit with a full budget.
         ACTIVE_VISITS.remove(id);
         return list;
      }

      ParcelVisit visit = ACTIVE_VISITS.get(id);
      if (visit == null || !visit.parcelId().equals(current.id())) {
         visit = new ParcelVisit(current.id(), now);
         ACTIVE_VISITS.put(id, visit);
      }

      long elapsed = now - visit.enteredAt();
      list.remove(current);
      if (elapsed > PARCEL_TIME_BUDGET_TICKS && !list.isEmpty()) {
         // Budget exceeded AND we have siblings worth trying — demote.
         list.add(current);
      } else {
         list.add(0, current);
      }
      return list;
   }

   /** Run the full priority table for a single parcel and fire the first
    *  applicable task. Dispatches to either {@link #tryAllForPlantParcel}
    *  or {@link #tryAllForAnimalParcel} based on {@link FieldRegion#type()}.
    *  The caller iterates parcels in "active-first" order so the active
    *  parcel is fully serviced before any other parcel is considered. */
   private static boolean tryAllForParcel(WorkProductionService.Ctx ctx, FieldRegion parcel,
                                          boolean haveShears, Item seed, boolean haveHoe) {
      return switch (parcel.type()) {
         case PLANT  -> tryAllForPlantParcel(ctx, parcel, seed, haveHoe);
         case ANIMAL -> tryAllForAnimalParcel(ctx, parcel, haveShears);
      };
   }

   /** Crop-farming routine. Harvest → water → plant → reclaim → till.
    *  Never touches animals (sheep/cow/etc. on a plant parcel are
    *  ignored — the player can fence them out, or rebind the parcel as
    *  ANIMAL).
    *
    *  The {@code seed} param is the fallback "any seed I'm carrying"
    *  pick from the global inventory scan; the planter PREFERS the
    *  plan-aware pick (per {@link com.yucareux.townfolk.town.CropPlan})
    *  and only uses the fallback when the plan can't be satisfied with
    *  what's currently in the bag. */
   private static boolean tryAllForPlantParcel(WorkProductionService.Ctx ctx, FieldRegion parcel,
                                                Item seed, boolean haveHoe) {
      var level = ctx.level();
      String pid = parcel.id();
      String actor = ctx.entry().name();

      BlockPos ripe = findRipeCropIn(level, parcel);
      if (ripe != null) {
         VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
            + " type=PLANT step=harvest pos=" + ripe.toShortString(), "");
         enqueueHarvestAt(ctx, parcel, ripe); return true;
      }

      BlockPos dry = findDryFarmlandIn(level, parcel);
      if (dry != null) {
         VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
            + " type=PLANT step=water pos=" + dry.toShortString(), "");
         enqueueWaterFor(ctx, parcel, dry); return true;
      }

      // Plan-aware seed pick: prefer the crop with the biggest deficit
      // on this parcel that the villager actually holds; fall back to
      // whatever's in the bag (covers parcels still on the default
      // wheat-only plan AND the "all my plan seeds are gone" edge case).
      Item planSeed = pickPlanAwareSeed(ctx, parcel);
      Item useSeed = planSeed != null ? planSeed : seed;
      if (useSeed != null) {
         BlockPos farm = findEmptyFarmlandIn(level, parcel);
         if (farm != null) {
            VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
               + " type=PLANT step=plant pos=" + farm.toShortString()
               + " seed=" + BuiltInRegistries.ITEM.getKey(useSeed), "");
            enqueuePlantAt(ctx, parcel, farm, useSeed); return true;
         }
      }

      BlockPos reclaimable = findReclaimableIn(level, parcel);
      if (reclaimable != null) {
         VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
            + " type=PLANT step=reclaim pos=" + reclaimable.toShortString(), "");
         enqueueReclaimAt(ctx, parcel, reclaimable); return true;
      }

      if (haveHoe && useSeed != null) {
         BlockPos tillable = findTillableIn(level, parcel);
         if (tillable != null) {
            VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
               + " type=PLANT step=till pos=" + tillable.toShortString(), "");
            enqueueTillAt(ctx, parcel, tillable); return true;
         }
      }

      VerboseLog.write("PARCEL_PICK_NONE", "actor=" + actor + " parcel=" + pid
         + " type=PLANT haveHoe=" + haveHoe + " haveSeed=" + (useSeed != null), "");
      return false;
   }

   /** Animal-husbandry routine. Iterates the {@link com.yucareux.townfolk.world.livestock.LivestockTasks}
    *  registry in priority order — shear, milk, breed sheep, breed cow,
    *  breed pig, breed chicken, breed rabbit. The first task whose tool
    *  the villager holds AND that has a ready target on the parcel
    *  wins. Adding a new species or interaction means appending a new
    *  {@link com.yucareux.townfolk.world.livestock.LivestockTask}; this
    *  method does not change.
    *
    *  Never tills the ground or plants crops, so grass stays as
    *  forage. */
   private static boolean tryAllForAnimalParcel(WorkProductionService.Ctx ctx, FieldRegion parcel,
                                                 boolean haveShears) {
      var level = ctx.level();
      String pid = parcel.id();
      String actor = ctx.entry().name();

      // Per-parcel plan: gates breeding (Stage 5). HARVEST tasks
      // (shear, milk) are unconditional — they're triggered by the
      // animal's own readiness, and capping them belongs to the
      // ProductionTargets system. BREED is the only behaviour the
      // animal-plan governs.
      com.yucareux.townfolk.town.AnimalPlan plan =
         com.yucareux.townfolk.town.AnimalPlanRegistry.find(level, parcel.id());

      StringBuilder trace = new StringBuilder();
      for (var task : com.yucareux.townfolk.world.livestock.LivestockTasks.ALL) {
         boolean hasTool = task.hasTool(ctx.actor());
         trace.append(task.id()).append("(tool=").append(hasTool).append(") ");
         if (!hasTool) continue;
         // BREED gating: skip the task entirely unless the plan opts
         // this species into BREED_UP AND we're still below target.
         if (task.verb().equals("breed")
             && !breedAllowedFor(level, parcel, task, plan)) {
            trace.append("[plan-gated] ");
            continue;
         }
         var target = task.findReadyTargetIn(level, parcel, ctx.actor());
         if (target == null) continue;
         VerboseLog.write("PARCEL_PICK", "actor=" + actor + " parcel=" + pid
            + " type=ANIMAL step=" + task.verb() + " task=" + task.id(), "");
         enqueueLivestockTask(ctx, parcel, task, target);
         return true;
      }

      VerboseLog.write("PARCEL_PICK_NONE", "actor=" + actor + " parcel=" + pid
         + " type=ANIMAL", "candidates: " + trace.toString().trim());
      return false;
   }

   /** True iff this breed task is permitted right now. Breeding is
    *  strict opt-in: the player must have explicitly set
    *  {@link com.yucareux.townfolk.town.AnimalPlan.Mode#BREED_UP} with
    *  a positive target via the animal-plan modal. No plan → no
    *  breeding (separate from harvest behaviour, which is unaffected).
    *
    *  <p>Decision tree:
    *  <ul>
    *    <li>No plan entry for this species → never breed.
    *    <li>Entry exists, mode = HOLD → never breed.
    *    <li>Entry exists, mode = BREED_UP, target = 0 → never breed.
    *    <li>Entry exists, mode = BREED_UP, target &gt; 0 → breed
    *        while current population &lt; target.
    *  </ul>
    *
    *  <p>Population count is adults + babies inside the parcel AABB,
    *  so a parcel with 6 adult cows + 2 calves reads "8 cows"
    *  against the target — keeps breeding from overshooting while
    *  juveniles are still maturing.
    *
    *  <p>Important: this gate is INDEPENDENT of any harvest production
    *  cap. Capping milk does not stop a herder from breeding their
    *  cows (and vice versa) — the two systems track different goals.
    *  Once population reaches target, breeding stops via this gate;
    *  harvest priority then naturally takes over.
    */
   private static boolean breedAllowedFor(net.minecraft.server.level.ServerLevel level,
                                          FieldRegion parcel,
                                          com.yucareux.townfolk.world.livestock.LivestockTask task,
                                          com.yucareux.townfolk.town.AnimalPlan plan) {
      // Resolve the task's target class back to an entity-type id.
      Class<? extends net.minecraft.world.entity.animal.Animal> cls = task.targetType();
      String speciesId = speciesIdFor(cls);
      if (speciesId == null) return false;

      var entry = plan.findSpecies(speciesId);
      if (entry.isEmpty()) return false;
      if (entry.get().mode() == com.yucareux.townfolk.town.AnimalPlan.Mode.HOLD) return false;
      int target = entry.get().targetCount();
      if (target <= 0) return false;

      // Count current population (adults + babies).
      var mn = parcel.scanMin(); var mx = parcel.scanMax();
      var aabb = new net.minecraft.world.phys.AABB(
         mn.getX(), mn.getY(), mn.getZ(),
         mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
      int now = level.getEntitiesOfClass(cls, aabb,
         a -> a.isAlive()).size();
      return now < target;
   }

   /** Map a livestock class to its vanilla entity-type id. Returns
    *  {@code null} for classes we don't know about — those will never
    *  be gated, which is fine since only Stage-5-listed species have
    *  BreedTasks in {@link com.yucareux.townfolk.world.livestock.LivestockTasks}. */
   private static String speciesIdFor(Class<? extends net.minecraft.world.entity.animal.Animal> cls) {
      if (cls == net.minecraft.world.entity.animal.Cow.class)     return "minecraft:cow";
      if (cls == net.minecraft.world.entity.animal.Sheep.class)   return "minecraft:sheep";
      if (cls == net.minecraft.world.entity.animal.Pig.class)     return "minecraft:pig";
      if (cls == net.minecraft.world.entity.animal.Chicken.class) return "minecraft:chicken";
      if (cls == net.minecraft.world.entity.animal.Rabbit.class)  return "minecraft:rabbit";
      return null;
   }

   /** Generic enqueue for any {@link com.yucareux.townfolk.world.livestock.LivestockTask}.
    *  Wraps the task's {@code perform} in an EntityTask that walks the
    *  villager into reach + runs the action + writes the memory line. */
   private static void enqueueLivestockTask(WorkProductionService.Ctx ctx, FieldRegion parcel,
                                            com.yucareux.townfolk.world.livestock.LivestockTask task,
                                            net.minecraft.world.entity.animal.Animal target) {
      var level = ctx.level();
      var town = ctx.town();
      EntityTaskQueue.enqueue(level, ctx.actor(), new EntityTaskQueue.EntityTask(
         ctx.actor().getUUID(), target.getUUID(),
         level.getGameTime() + 20L * 30, task.verb(),
         (lvl, v, t) -> {
            if (!(t instanceof net.minecraft.world.entity.animal.Animal a)) {
               throw new RuntimeException("livestock target gone");
            }
            return task.perform(lvl, v, a, parcel, town);
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=" + task.id(), "");
   }

   // ───── auto-fetch from town treasury ─────

   /** Write a {@code [need:barrel_for_<item>]} todo so NeedsService hails
    *  the player. One per villager per item per day — same dedupe
    *  pattern as the {@code [need:hoe]} / {@code [need:seeds]} memories. */
   private static final java.util.Map<java.util.UUID, java.util.Map<String, Long>> NEED_BARREL_WRITES =
      new java.util.concurrent.ConcurrentHashMap<>();
   private static void writeNeedBarrelMemory(WorkProductionService.Ctx ctx, String itemKey) {
      var level = ctx.level();
      long currentDay = level.getGameTime() / 24000L;
      var perVillager = NEED_BARREL_WRITES.computeIfAbsent(ctx.actor().getUUID(),
         k -> new java.util.concurrent.ConcurrentHashMap<>());
      Long prev = perVillager.get(itemKey);
      if (prev != null && prev == currentDay) return;
      perVillager.put(itemKey, currentDay);
      String shortItem = itemKey.contains(":")
         ? itemKey.substring(itemKey.indexOf(':') + 1) : itemKey;
      String text = "I have " + shortItem.replace('_', ' ')
         + " to bank but no registered barrel accepts it. The player should "
         + "sneak+right-click a chest with an empty hand to configure it.";
      MemoryStore.write(ctx.actor(), "need:barrel_for_" + shortItem, currentDay, text);
      VerboseLog.write("NEED_BARREL", "actor=" + ctx.entry().name() + " item=" + shortItem, text);

      // Tagged todo so NeedsService hails the player. Same pattern as
      // closeSatisfiedNeeds / maybeWriteLack for hoes/seeds.
      var comp = ctx.actor().getData(
         com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      String tag = "[need:barrel_for_" + shortItem + "] ";
      boolean already = comp.todos().stream()
         .anyMatch(t -> t.isOpen() && t.text().startsWith(tag));
      if (already) return;
      var todo = new com.yucareux.townfolk.villager.Todo(
         java.util.UUID.randomUUID().toString(),
         tag + text, "player", "open", currentDay);
      ctx.actor().setData(
         com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get(),
         comp.withAppendedTodo(todo));
   }

   /** When the routine can't act because the villager lacks a tool/seed,
    *  consult {@link com.yucareux.townfolk.town.TownTreasury} for the nearest
    *  barrel that has one. If found, emit a {@code withdraw} verb via
    *  {@link ToolDispatcher} — which auto-routes a walk-to-barrel BlockTask
    *  and does the actual transfer on arrival. Next routine tick the
    *  villager has the item and the normal rules fire.
    *
    *  Returns true if a fetch was kicked off (caller treats it like a fired
    *  task). */
   private static boolean tryFetchMissingResource(WorkProductionService.Ctx ctx,
                                                   boolean haveSeed, boolean haveHoe) {
      // Walk the declarative WorkNeed list. Each need self-reports
      // whether it applies + does its own treasury lookup. Adding a new
      // profession's tool need (bucket for cow milking, axe for
      // lumber, pickaxe for mining, rod for fishing, …) is a single
      // new entry in {@link #WORK_NEEDS} — no edits to this method.
      StringBuilder trace = new StringBuilder();
      for (WorkNeed need : WORK_NEEDS) {
         boolean wants;
         try { wants = need.wanted(ctx); }
         catch (Throwable t) {
            VerboseLog.write("WORK_NEED_ERROR",
               "actor=" + ctx.entry().name() + " need=" + need.id(),
               "wanted() threw: " + t);
            continue;
         }
         trace.append(need.id()).append("=").append(wants).append(' ');
         if (!wants) continue;
         try {
            if (need.tryFetch(ctx)) {
               VerboseLog.write("PARCEL_FETCH_NEEDS",
                  "actor=" + ctx.entry().name() + " fired=" + need.id(),
                  "needs: " + trace);
               return true;
            }
         } catch (Throwable t) {
            VerboseLog.write("WORK_NEED_ERROR",
               "actor=" + ctx.entry().name() + " need=" + need.id(),
               "tryFetch() threw: " + t);
         }
      }
      VerboseLog.write("PARCEL_FETCH_NEEDS",
         "actor=" + ctx.entry().name() + " fired=none",
         "needs: " + trace);
      return false;
   }

   // ───── Work-need declarative registry ─────

   /** A self-contained "this villager needs resource X" record. Each
    *  WorkNeed encapsulates the predicate that decides when it fires
    *  AND the fetch action it dispatches when triggered. The fetch
    *  routine just walks {@link #WORK_NEEDS} in priority order — new
    *  professions add their tool needs by appending to that list. */
   private interface WorkNeed {
      /** Short identifier for logs ("shears", "hoe", "seed", future
       *  "bucket", "axe", "pickaxe", "rod", ...). */
      String id();
      /** True when the villager currently lacks the resource AND has
       *  parcel state that requires it. Cheap — runs every tryTick. */
      boolean wanted(WorkProductionService.Ctx ctx);
      /** Issue the fetch verb if the treasury has a source. Returns
       *  true if a verb was emitted. */
      boolean tryFetch(WorkProductionService.Ctx ctx);
   }

   /** Need ordering = priority. Time-sensitive renewable resources
    *  (shears for wool, bucket for milk) come before tilling tools
    *  (hoe), which come before bulk crops (seeds). The fetch loop
    *  fires the first matching need per tick; the next tick handles
    *  the next.
    *
    *  The livestock entries are MATERIALIZED from
    *  {@link com.yucareux.townfolk.world.livestock.LivestockTasks#TOOL_FETCH}
    *  so adding a species or interaction never edits this list. */
   private static final java.util.List<WorkNeed> WORK_NEEDS = buildWorkNeeds();

   private static java.util.List<WorkNeed> buildWorkNeeds() {
      java.util.List<WorkNeed> out = new java.util.ArrayList<>();
      for (var task : com.yucareux.townfolk.world.livestock.LivestockTasks.TOOL_FETCH) {
         out.add(new LivestockToolNeed(task));
      }
      // Breeding food fetch — separate from LivestockTasks.TOOL_FETCH
      // because Breed tasks query Animal.isFood() dynamically and
      // don't expose a fixed tool id. Without this need, a herder
      // with active BREED_UP plans but no wheat/carrot in their bag
      // sits idle indefinitely even though storage has the food.
      out.add(new BreedFoodNeed());
      out.add(new HoeNeed());
      out.add(new SeedNeed());
      // Future: new AxeNeed(), new PickaxeNeed(), new RodNeed(), …
      return java.util.Collections.unmodifiableList(out);
   }

   /** Primary breeding food per species. Used by {@link BreedFoodNeed}
    *  to pull the right item from town storage when an ANIMAL parcel
    *  has an active BREED_UP plan but the herder has run out of food.
    *  Returns {@code null} for species we don't have a Stage-5 BreedTask
    *  for — they're not subject to the player's plan gate. */
   private static String primaryBreedFood(String speciesId) {
      return switch (speciesId) {
         case "minecraft:cow", "minecraft:sheep" -> "minecraft:wheat";
         case "minecraft:pig"                    -> "minecraft:carrot";
         case "minecraft:chicken"                -> "minecraft:wheat_seeds";
         case "minecraft:rabbit"                 -> "minecraft:carrot";
         default                                 -> null;
      };
   }

   private static boolean bagHasItem(net.minecraft.world.entity.npc.Villager v, String itemId) {
      var item = BuiltInRegistries.ITEM.get(
         net.minecraft.resources.ResourceLocation.parse(itemId));
      var inv = v.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         var s = inv.getItem(i);
         if (!s.isEmpty() && s.getItem() == item) return true;
      }
      return false;
   }

   /** Auto-fetch breeding food for any ANIMAL parcel with an active
    *  BREED_UP plan whose population is below target. Walks the
    *  herder's parcels, picks the first species in need, and routes
    *  a {@code withdraw} verb for that species' primary food via
    *  the existing treasury-routing path. */
   private static final class BreedFoodNeed implements WorkNeed {
      @Override public String id() { return "breed_food"; }

      @Override public boolean wanted(WorkProductionService.Ctx ctx) {
         return findNeededFood(ctx) != null;
      }

      @Override public boolean tryFetch(WorkProductionService.Ctx ctx) {
         String foodId = findNeededFood(ctx);
         if (foodId == null) return false;
         var item = BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse(foodId));
         var hit = com.yucareux.townfolk.town.TownTreasury.findNearestWith(
            ctx.level(), ctx.actor().blockPosition(), item);
         if (hit.isEmpty()) return false;
         String path = foodId.substring(foodId.indexOf(':') + 1);
         // Fetch a half-stack so the herder doesn't have to make a
         // round trip per breed event. 16 is plenty for several
         // breeding rounds; refetches happen automatically when the
         // bag empties.
         return fireFetch(ctx, path, 16);
      }

      /** Identify the species-food this herder needs right now. Returns
       *  null when no breeding food is needed (no active plans, all at
       *  target, no eligible pairs, bag already stocked). */
      private static String findNeededFood(WorkProductionService.Ctx ctx) {
         var level = ctx.level();
         for (FieldRegion p : ctx.comp().parcels()) {
            if (p.type() != FieldRegion.Type.ANIMAL) continue;
            var plan = com.yucareux.townfolk.town.AnimalPlanRegistry.find(level, p.id());
            for (var entry : plan.entries()) {
               if (entry.mode() != com.yucareux.townfolk.town.AnimalPlan.Mode.BREED_UP) continue;
               if (entry.targetCount() <= 0) continue;
               String foodId = primaryBreedFood(entry.speciesId());
               if (foodId == null) continue;
               if (bagHasItem(ctx.actor(), foodId)) continue;  // already stocked

               // Confirm there's breeding work waiting (population below
               // target AND at least 2 adults to breed). Otherwise we'd
               // fetch wheat for no reason.
               var rl = net.minecraft.resources.ResourceLocation.tryParse(entry.speciesId());
               if (rl == null) continue;
               var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(rl);
               if (type == null) continue;
               var mn = p.scanMin(); var mx = p.scanMax();
               var aabb = new net.minecraft.world.phys.AABB(
                  mn.getX(), mn.getY(), mn.getZ(),
                  mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
               int total = level.getEntitiesOfClass(
                     net.minecraft.world.entity.animal.Animal.class, aabb,
                     a -> a.isAlive() && a.getType() == type).size();
               if (total >= entry.targetCount()) continue;
               int adults = level.getEntitiesOfClass(
                     net.minecraft.world.entity.animal.Animal.class, aabb,
                     a -> a.isAlive() && !a.isBaby() && a.getType() == type).size();
               if (adults < 2) continue;
               return foodId;
            }
         }
         return null;
      }
   }

   /** Auto-fetch tool for a single {@link com.yucareux.townfolk.world.livestock.LivestockTask}.
    *  Wants the tool when: villager lacks it AND any ANIMAL parcel has
    *  a target this task could act on if the villager had the tool.
    *  Fetches via item-id or tag depending on what the task declares. */
   private static final class LivestockToolNeed implements WorkNeed {
      private final com.yucareux.townfolk.world.livestock.LivestockTask task;
      LivestockToolNeed(com.yucareux.townfolk.world.livestock.LivestockTask task) { this.task = task; }
      @Override public String id() { return task.id() + ":tool"; }
      @Override public boolean wanted(WorkProductionService.Ctx ctx) {
         if (task.hasTool(ctx.actor())) return false;
         for (FieldRegion p : ctx.comp().parcels()) {
            if (p.type() != FieldRegion.Type.ANIMAL) continue;
            // hasTool is the only gate on findReadyTargetIn — we want to
            // know if there'd be work IF we had the tool. Probe with a
            // pseudo-villager flag by temporarily ignoring tool? Cheap
            // alternative: just check if any individual of the species
            // exists, leaving the precise readiness for the action step.
            var mn = p.scanMin(); var mx = p.scanMax();
            var aabb = new net.minecraft.world.phys.AABB(
               mn.getX(), mn.getY(), mn.getZ(),
               mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
            if (!ctx.level().getEntitiesOfClass(task.targetType(), aabb,
                  a -> task.ready(ctx.level(), a, ctx.actor())
                       || a.isAlive() && !a.isBaby()).isEmpty()) {
               // There IS at least one adult of the species on this parcel —
               // worth fetching the tool. The action-time readiness check
               // will filter precisely.
               return true;
            }
         }
         return false;
      }
      @Override public boolean tryFetch(WorkProductionService.Ctx ctx) {
         String itemId = task.toolItemId();
         if (itemId != null) {
            var item = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(itemId));
            var hit = com.yucareux.townfolk.town.TownTreasury.findNearestWith(
               ctx.level(), ctx.actor().blockPosition(), item);
            if (hit.isEmpty()) return false;
            String path = itemId.substring(itemId.indexOf(':') + 1);
            return fireFetch(ctx, path, 1);
         }
         var tag = task.toolTag();
         if (tag != null) {
            var hit = com.yucareux.townfolk.town.TownTreasury.findNearestWithTagged(
               ctx.level(), ctx.actor().blockPosition(), tag);
            if (hit.isEmpty()) return false;
            String path = BuiltInRegistries.ITEM.getKey(hit.get().item()).getPath();
            return fireFetch(ctx, path, 1);
         }
         return false;
      }
   }

   private static final class HoeNeed implements WorkNeed {
      @Override public String id() { return "hoe"; }
      @Override public boolean wanted(WorkProductionService.Ctx ctx) {
         if (hasToolWithTag(ctx.actor(), net.minecraft.tags.ItemTags.HOES)) return false;
         for (FieldRegion p : ctx.comp().parcels()) {
            if (p.type() != FieldRegion.Type.PLANT) continue;
            if (findTillableIn(ctx.level(), p) != null) return true;
         }
         return false;
      }
      @Override public boolean tryFetch(WorkProductionService.Ctx ctx) {
         var hit = com.yucareux.townfolk.town.TownTreasury.findNearestWithTagged(
            ctx.level(), ctx.actor().blockPosition(), net.minecraft.tags.ItemTags.HOES);
         if (hit.isEmpty()) return false;
         String path = BuiltInRegistries.ITEM.getKey(hit.get().item()).getPath();
         return fireFetch(ctx, path, 1);
      }
   }

   /** Plan-aware seed need. Walks every owned PLANT parcel, computes
    *  per-crop deficits against {@link com.yucareux.townfolk.town.CropPlan},
    *  and treats "missing the seed for the biggest unmet deficit" as a
    *  fetch trigger. So a villager with the default wheat-only plan
    *  behaves identically to the old single-seed flow, while a villager
    *  whose plan calls for carrots + beetroots makes round trips for
    *  each species in deficit order.
    *
    *  Two-tier wanted() check:
    *    1. Plan-aware: any active plan entry whose seed we lack AND the
    *       parcel has plantable space OR (haveHoe AND tillable space).
    *    2. Legacy fallback: parcel has plantable space and we have NO
    *       seed at all — covers parcels whose plan resolves to empty
    *       (player removed all rows) without leaving the villager
    *       stranded.
    *
    *  tryFetch picks the seed with the biggest projected deficit that
    *  has a registered barrel source. Falls back to "any crop seed in
    *  any barrel" if no plan-specified seed is sourceable. */
   private static final class SeedNeed implements WorkNeed {
      @Override public String id() { return "seed"; }
      @Override public boolean wanted(WorkProductionService.Ctx ctx) {
         boolean haveHoe = hasToolWithTag(ctx.actor(), net.minecraft.tags.ItemTags.HOES);
         boolean haveAnySeed = findSeed(ctx.actor().getInventory()) != null;
         for (FieldRegion p : ctx.comp().parcels()) {
            if (p.type() != FieldRegion.Type.PLANT) continue;
            boolean hasEmpty = findEmptyFarmlandIn(ctx.level(), p) != null;
            boolean hasTillable = findTillableIn(ctx.level(), p) != null;
            if (!hasEmpty && !(haveHoe && hasTillable)) continue;
            // Plan-aware: any plan entry whose seed we don't hold counts.
            // Skip entries that aren't crop seeds — they can't be
            // fulfilled and would spam the fetch loop with garbage.
            var plan = com.yucareux.townfolk.town.CropPlanRegistry.find(ctx.level(), p.id());
            for (var entry : plan.activeEntries()) {
               Item resolved = entry.resolveItem();
               if (resolved == null
                   || !(resolved instanceof BlockItem bi && bi.getBlock() instanceof CropBlock)) {
                  continue;
               }
               if (!hasItemId(ctx.actor(), entry.seedItemId())) return true;
            }
            // Legacy fallback: empty plan AND no seed in bag — still
            // worth fetching something so the parcel doesn't stall.
            if (!haveAnySeed && plan.activeEntries().isEmpty()) return true;
         }
         return false;
      }
      @Override public boolean tryFetch(WorkProductionService.Ctx ctx) {
         var level = ctx.level();
         String wantedSeed = pickBiggestDeficitSeed(ctx);
         if (wantedSeed != null) {
            var loc = net.minecraft.resources.ResourceLocation.tryParse(wantedSeed);
            if (loc != null) {
               Item it = BuiltInRegistries.ITEM.get(loc);
               var hit = com.yucareux.townfolk.town.TownTreasury.findNearestWith(
                  level, ctx.actor().blockPosition(), it);
               if (hit.isPresent()) {
                  String path = wantedSeed.substring(wantedSeed.indexOf(':') + 1);
                  return fireFetch(ctx, path, 32);
               }
            }
         }
         // Wanted seed isn't sourceable anywhere. If the villager
         // ALREADY holds some other seed, don't fall through to the
         // "any seed" scan — that would just re-fetch the same wrong
         // crop in a tight loop (fetch → deposit triggered by
         // threshold → fetch → repeat). Let the planter use what
         // they have and the player can route carrots into a
         // registered barrel to break the stall.
         if (findSeed(ctx.actor().getInventory()) != null) return false;
         // No seed at all. Last-resort: any crop seed in any barrel.
         // Same as the pre-plan behaviour — keeps the work-need loop
         // from getting stuck when the plan can't be satisfied yet.
         for (var e : com.yucareux.townfolk.town.StorageRegistry.entries(level)) {
            var pos = net.minecraft.core.BlockPos.of(e.getKey());
            var be = level.getBlockEntity(pos);
            if (!(be instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
               var stack = c.getItem(i);
               if (stack.isEmpty()) continue;
               Item it = stack.getItem();
               if (it instanceof net.minecraft.world.item.BlockItem bi
                   && bi.getBlock() instanceof CropBlock) {
                  int batch = Math.min(32, stack.getCount());
                  String path = BuiltInRegistries.ITEM.getKey(it).getPath();
                  return fireFetch(ctx, path, batch);
               }
            }
         }
         return false;
      }

      /** Across all owned PLANT parcels, find the seed whose deficit
       *  (target share minus current share, weighted by parcel size)
       *  is largest. Returns null if no plan-listed seed is in
       *  deficit anywhere. */
      private static String pickBiggestDeficitSeed(WorkProductionService.Ctx ctx) {
         String best = null;
         double bestScore = 0.0001;       // require a strictly-positive deficit
         for (FieldRegion p : ctx.comp().parcels()) {
            if (p.type() != FieldRegion.Type.PLANT) continue;
            var plan = com.yucareux.townfolk.town.CropPlanRegistry.find(ctx.level(), p.id());
            var active = plan.activeEntries();
            if (active.isEmpty()) continue;
            int totalWeight = plan.totalWeight();
            var counts = countCropsOnParcel(ctx.level(), p);
            int totalPlanted = 0;
            for (int n : counts.values()) totalPlanted += n;
            // Weight parcels by their size so a 32×32 carrot deficit
            // outranks a 4×4 carrot deficit on the priority queue.
            double sizeWeight = Math.max(1, p.sizeX() * (double) p.sizeZ());
            for (var entry : active) {
               // Same crop-seed guard as the planter — refusing to
               // chase a non-seed entry through the fetch loop.
               Item resolved = entry.resolveItem();
               if (resolved == null
                   || !(resolved instanceof BlockItem bi && bi.getBlock() instanceof CropBlock)) {
                  continue;
               }
               if (hasItemId(ctx.actor(), entry.seedItemId())) continue;
               double target = entry.weight() / (double) totalWeight;
               double current = totalPlanted == 0 ? 0.0
                  : counts.getOrDefault(entry.seedItemId(), 0) / (double) totalPlanted;
               double score = (target - current) * sizeWeight;
               if (score > bestScore) {
                  bestScore = score;
                  best = entry.seedItemId();
               }
            }
         }
         return best;
      }
   }

   /** Emit a {@code withdraw 1 <item>} verb. The verb's treasury auto-routing
    *  takes care of walking the villager to the nearest barrel that has it. */
   private static boolean fireFetch(WorkProductionService.Ctx ctx, String itemPath, int count) {
      // We reach into ToolDispatcher directly so the verb runs through its
      // normal path (treasury routing + after-verb reflexes + feedback).
      ToolDispatcher.execute(ctx.level(), ctx.town(), ctx.actor(), ctx.entry(),
         "withdraw " + count + " " + itemPath);
      VerboseLog.write("PARCEL_FETCH", "actor=" + ctx.entry().name()
         + " item=" + itemPath, "");
      return true;
   }

   // ───── parcel scans ─────

   private static BlockPos findRipeCropIn(net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               BlockState s = level.getBlockState(cur);
               if (s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s)) {
                  return cur.immutable();
               }
            }
         }
      }
      return null;
   }

   /** Farmland whose moisture isn't yet 7 (vanilla saturated) AND has no
    *  natural water source within 4 blocks. Skips already-hydrated tiles so
    *  villagers don't dig pointless trenches next to streams. */
   private static BlockPos findDryFarmlandIn(net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               BlockState s = level.getBlockState(cur);
               if (!s.is(Blocks.FARMLAND)) continue;
               if (s.getValue(net.minecraft.world.level.block.FarmBlock.MOISTURE) >= 7) continue;
               if (anyWaterWithin(level, cur, 4)) continue;
               return cur.immutable();
            }
         }
      }
      return null;
   }

   /** Tillable surface = grass/dirt/coarse-dirt with EITHER air directly
    *  above OR a clearable plant tuft (short grass, fern, dead bush,
    *  snow layer) that the till operation will rip out before placing
    *  farmland. Without the tuft-tolerant branch, a grass block with a
    *  visual grass tuft on top is never tillable — which both stalls
    *  preparation AND used to make the reclaim step mis-place dirt
    *  ON TOP of the grass. */
   private static BlockPos findTillableIn(net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               BlockState s = level.getBlockState(cur);
               if (!(s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT))) continue;
               BlockState above = level.getBlockState(cur.above());
               if (!above.isAir() && !isClearableSurfaceTuft(above)) continue;
               return cur.immutable();
            }
         }
      }
      return null;
   }

   /** Small ground-cover plants the till step destroys before placing
    *  farmland. Distinct from "real" plants we leave alone (saplings,
    *  flowers, crops) — these are the natural-world tufts that just
    *  get in the way of farming. */
   private static boolean isClearableSurfaceTuft(BlockState s) {
      return s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
          || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN)
          || s.is(Blocks.DEAD_BUSH) || s.is(Blocks.SNOW);
   }

   /** Same hydration radius vanilla uses (horizontal 4, vertical 0–1). */
   private static boolean anyWaterWithin(net.minecraft.server.level.ServerLevel level, BlockPos centre, int r) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      for (int dx = -r; dx <= r; dx++)
      for (int dz = -r; dz <= r; dz++)
      for (int dy = 0; dy <= 1; dy++) {
         cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
         if (level.getFluidState(cur).is(net.minecraft.tags.FluidTags.WATER)) return true;
      }
      return false;
   }

   private static boolean hasToolWithTag(Villager actor, net.minecraft.tags.TagKey<Item> tag) {
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && s.is(tag)) return true;
      }
      return false;
   }

   private static BlockPos findEmptyFarmlandIn(net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               if (!level.getBlockState(cur).is(Blocks.FARMLAND)) continue;
               if (!level.getBlockState(cur.above()).isAir()) continue;
               return cur.immutable();
            }
         }
      }
      return null;
   }

   private static Item findSeed(SimpleContainer inv) {
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) continue;
         if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock) {
            return s.getItem();
         }
      }
      return null;
   }

   /** Find a specific seed item in the villager's bag. */
   private static boolean hasItemId(Villager actor, String itemId) {
      var loc = net.minecraft.resources.ResourceLocation.tryParse(itemId);
      if (loc == null) return false;
      Item target = BuiltInRegistries.ITEM.get(loc);
      if (target == null) return false;
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == target) return true;
      }
      return false;
   }

   /** Count existing crop blocks on the parcel, keyed by their seed
    *  item id. Used by the plan-aware planter to compute each crop's
    *  current share, which feeds the deficit pick. Modded crops that
    *  don't map to a known seed item (see
    *  {@link com.yucareux.townfolk.town.CropPlan#seedItemIdFor}) are
    *  silently skipped — they grow and harvest fine, they just don't
    *  inform replant ratios. */
   private static java.util.Map<String, Integer> countCropsOnParcel(
         net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               var state = level.getBlockState(cur);
               if (!(state.getBlock() instanceof CropBlock)) continue;
               String seed = com.yucareux.townfolk.town.CropPlan.seedItemIdFor(state.getBlock());
               if (seed != null) counts.merge(seed, 1, Integer::sum);
            }
         }
      }
      return counts;
   }

   /** Choose the next seed to plant on this parcel using the
    *  plan's target-tracking algorithm:
    *
    *    1. Count existing crops by seed on the parcel.
    *    2. For each active plan entry, compute
    *         targetShare = weight / totalWeight
    *         currentShare = count / totalPlanted
    *         deficit = targetShare - currentShare
    *    3. Pick the entry with the biggest positive deficit that the
    *       villager actually holds a seed for.
    *    4. If nothing in the plan is feasible (missing seeds across
    *       the board), fall back to {@link #findSeed} — i.e. plant
    *       whatever seed they hold so the tile doesn't sit empty.
    *
    *  The fallback only ever fires when the seed-fetch pass also
    *  failed to source the wanted seed (the registry has no barrel
    *  holding it). When the missing seed arrives later, the deficit
    *  it accumulated yells loudest, so the field rebalances on the
    *  next planting tick. */
   private static Item pickPlanAwareSeed(WorkProductionService.Ctx ctx, FieldRegion parcel) {
      var plan = com.yucareux.townfolk.town.CropPlanRegistry.find(ctx.level(), parcel.id());
      var active = plan.activeEntries();
      if (active.isEmpty()) return findSeed(ctx.actor().getInventory());

      var counts = countCropsOnParcel(ctx.level(), parcel);
      int totalPlanted = 0;
      for (int n : counts.values()) totalPlanted += n;
      int totalWeight = plan.totalWeight();

      String chosen = null;
      double bestDeficit = Double.NEGATIVE_INFINITY;
      for (var e : active) {
         // Validate: skip entries whose item isn't a crop seed (defends
         // against the player dragging a non-seed item into a plan
         // slot — without this guard, enqueuePlantAt would happily
         // place dirt onto the farmland).
         Item resolved = e.resolveItem();
         if (resolved == null
             || !(resolved instanceof BlockItem bi && bi.getBlock() instanceof CropBlock)) {
            continue;
         }
         if (!hasItemId(ctx.actor(), e.seedItemId())) continue;
         double target = e.weight() / (double) totalWeight;
         double current = totalPlanted == 0 ? 0.0
            : counts.getOrDefault(e.seedItemId(), 0) / (double) totalPlanted;
         double deficit = target - current;
         if (deficit > bestDeficit) {
            bestDeficit = deficit;
            chosen = e.seedItemId();
         }
      }
      if (chosen == null) {
         // No plan-listed seed is in hand right now — fall back to
         // anything we have so the tile gets used. Seed-fetch will
         // address the wanted-but-missing crop next tick.
         return findSeed(ctx.actor().getInventory());
      }
      var loc = net.minecraft.resources.ResourceLocation.tryParse(chosen);
      return loc == null ? findSeed(ctx.actor().getInventory()) : BuiltInRegistries.ITEM.get(loc);
   }

   // ───── task dispatchers ─────

   private static void enqueueHarvestAt(WorkProductionService.Ctx ctx,
                                        FieldRegion parcel, BlockPos pos) {
      var level = ctx.level();
      BlockTaskQueue.enqueue(level, ctx.actor(), new BlockTaskQueue.BlockTask(
         ctx.actor().getUUID(), pos,
         level.getGameTime() + 20L * 30, "harvest",
         (lvl, v, p) -> {
            BlockState state = lvl.getBlockState(p);
            var drops = Block.getDrops(state, lvl, p, null, v, v.getMainHandItem());
            lvl.destroyBlock(p, false, v);

            int total = 0;
            String first = null;
            for (ItemStack drop : drops) {
               if (drop.isEmpty()) continue;
               if (first == null) first = BuiltInRegistries.ITEM.getKey(drop.getItem()).getPath();
               total += drop.getCount();
               ItemStack leftover = v.getInventory().addItem(drop);
               if (leftover != null && !leftover.isEmpty()) {
                  var e = new ItemEntity(lvl, v.getX(), v.getY(), v.getZ(), leftover);
                  e.setPickUpDelay(20);
                  lvl.addFreshEntity(e);
               }
            }

            // Atomic replant on the same tile if we still have a seed.
            String replantNote = "";
            if (lvl.getBlockState(p.below()).is(Blocks.FARMLAND)
                && lvl.getBlockState(p).isAir()) {
               Item seed = findSeed(v.getInventory());
               if (seed instanceof BlockItem bi) {
                  lvl.setBlockAndUpdate(p, bi.getBlock().defaultBlockState());
                  takeItem(v.getInventory(), BuiltInRegistries.ITEM.getKey(seed).toString(), 1);
                  replantNote = " + replanted " + BuiltInRegistries.ITEM.getKey(seed).getPath();
               }
            }
            long day = lvl.getGameTime() / 24000L;
            String parcelTag = parcel.shortLabel(ctx.town().getBlockPos());
            MemoryStore.write(v, "work", day,
               "At my " + parcelTag + " I harvested " + total + "× " + first
                  + (replantNote.isEmpty() ? "" : " and replanted the row") + ".");
            return "harvested " + total + "× " + first + replantNote;
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=harvest pos=" + pos.toShortString(), "");
      ctx.town().getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
         ctx.entry().name() + " heads to harvest at " + pos.toShortString()
            + " (" + parcel.shortLabel(ctx.town().getBlockPos()) + ")");
   }

   private static void enqueuePlantAt(WorkProductionService.Ctx ctx,
                                      FieldRegion parcel, BlockPos farm, Item seed) {
      var level = ctx.level();
      BlockPos plantAt = farm.above();
      Item seedF = seed;
      BlockTaskQueue.enqueue(level, ctx.actor(), new BlockTaskQueue.BlockTask(
         ctx.actor().getUUID(), farm,
         level.getGameTime() + 20L * 30, "plant",
         (lvl, v, pos) -> {
            if (!lvl.getBlockState(plantAt).isAir())
               throw new RuntimeException("farmland above is occupied");
            if (!(seedF instanceof BlockItem bi))
               throw new RuntimeException("seed isn't a placeable block");
            lvl.setBlockAndUpdate(plantAt, bi.getBlock().defaultBlockState());
            takeItem(v.getInventory(), BuiltInRegistries.ITEM.getKey(seedF).toString(), 1);
            long day = lvl.getGameTime() / 24000L;
            String parcelTag = parcel.shortLabel(ctx.town().getBlockPos());
            MemoryStore.write(v, "work", day,
               "At my " + parcelTag + " I planted a "
                  + BuiltInRegistries.ITEM.getKey(seedF).getPath() + ".");
            return "planted " + BuiltInRegistries.ITEM.getKey(seedF).getPath();
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=plant pos=" + farm.toShortString(), "");
   }

   private static void enqueueTillAt(WorkProductionService.Ctx ctx, FieldRegion parcel, BlockPos pos) {
      var level = ctx.level();
      BlockTaskQueue.enqueue(level, ctx.actor(), new BlockTaskQueue.BlockTask(
         ctx.actor().getUUID(), pos,
         level.getGameTime() + 20L * 30, "till",
         (lvl, v, p) -> {
            // Clear any surface tuft (short grass, fern, dead bush,
            // snow) sitting on top of the soil first — otherwise the
            // farmland placement below would either fail or visually
            // leave a tuft floating above farmland.
            BlockState aboveState = lvl.getBlockState(p.above());
            String clearedNote = "";
            if (isClearableSurfaceTuft(aboveState)) {
               String tuftKind = BuiltInRegistries.BLOCK.getKey(aboveState.getBlock()).getPath();
               lvl.destroyBlock(p.above(), true, v);
               clearedNote = " (cleared " + tuftKind + ")";
            }
            lvl.setBlockAndUpdate(p, Blocks.FARMLAND.defaultBlockState());
            long day = lvl.getGameTime() / 24000L;
            String parcelTag = parcel.shortLabel(ctx.town().getBlockPos());
            MemoryStore.write(v, "work", day,
               "At my " + parcelTag + " I tilled the soil at " + p.toShortString()
                  + clearedNote + ".");
            return "tilled the soil at " + p.toShortString() + clearedNote;
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=till pos=" + pos.toShortString(), "");
   }

   private static void enqueueWaterFor(WorkProductionService.Ctx ctx, FieldRegion parcel, BlockPos farmland) {
      var level = ctx.level();
      // Pick a viable source-tile near the farmland — prefer cardinal-adjacent
      // at same Y, air-filled, supported.
      BlockPos waterPos = pickWaterSourceFor(level, farmland);
      if (waterPos == null) {
         long day = level.getGameTime() / 24000L;
         MemoryStore.write(ctx.actor(), "work", day,
            "At my " + parcel.shortLabel(ctx.town().getBlockPos())
            + " I'd water the farmland but I can't find a safe spot to dig the source.");
         return;
      }
      BlockPos farmlandFinal = farmland;
      BlockTaskQueue.enqueue(level, ctx.actor(), new BlockTaskQueue.BlockTask(
         ctx.actor().getUUID(), waterPos,
         level.getGameTime() + 20L * 30, "water",
         (lvl, v, p) -> {
            BlockState existing = lvl.getBlockState(p);
            String breakNote = "";
            if (!existing.isAir() && !existing.is(net.minecraft.tags.BlockTags.REPLACEABLE)) {
               var drops = net.minecraft.world.level.block.Block.getDrops(
                  existing, lvl, p, null, v, v.getMainHandItem());
               lvl.destroyBlock(p, false, v);
               for (ItemStack d : drops) {
                  if (d.isEmpty()) continue;
                  ItemStack leftover = v.getInventory().addItem(d);
                  if (leftover != null && !leftover.isEmpty()) {
                     var ie = new ItemEntity(lvl, v.getX(), v.getY(), v.getZ(), leftover);
                     ie.setPickUpDelay(20);
                     lvl.addFreshEntity(ie);
                  }
               }
               breakNote = " (excavated first)";
            }
            lvl.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
            long day = lvl.getGameTime() / 24000L;
            MemoryStore.write(v, "work", day,
               "At my " + parcel.shortLabel(ctx.town().getBlockPos())
                  + " I irrigated the farmland by placing water at " + p.toShortString() + ".");
            return "placed water at " + p.toShortString() + breakNote + " (for farmland "
               + farmlandFinal.toShortString() + ")";
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=water pos=" + waterPos.toShortString(), "");
   }

   private static BlockPos pickWaterSourceFor(net.minecraft.server.level.ServerLevel level, BlockPos farmland) {
      int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1},{2,0},{-2,0},{0,2},{0,-2}};
      BlockPos best = null;
      double bestRank = Double.MAX_VALUE;
      for (int[] o : offsets) {
         BlockPos p = farmland.offset(o[0], 0, o[1]);
         var s = level.getBlockState(p);
         if (level.getBlockState(p.below()).isAir()) continue;
         double rank;
         if (s.isAir()) rank = 0;
         else if (s.is(net.minecraft.tags.BlockTags.REPLACEABLE)) rank = 1;
         else if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)) rank = 2;
         else continue;
         if (rank < bestRank) { bestRank = rank; best = p; }
      }
      return best;
   }

   /** When the routine has nothing actionable, examine the parcels and write
    *  a "I need X" memory the LLM will see at dialogue / autonomy time. One
    *  write per villager per in-game day per missing-resource — tracked in
    *  a transient static map. The LLM weaves the need into conversation
    *  ("by the way, I could use a hoe — there's good soil to till"). */
   private static final java.util.Map<java.util.UUID, java.util.Map<String, Long>> LACK_WRITES =
      new java.util.concurrent.ConcurrentHashMap<>();

   private static void writeLackingMemoryIfBlocked(WorkProductionService.Ctx ctx,
                                                    boolean haveSeed, boolean haveHoe) {
      var level = ctx.level();
      long currentDay = level.getGameTime() / 24000L;
      var perVillager = LACK_WRITES.computeIfAbsent(ctx.actor().getUUID(),
         k -> new java.util.concurrent.ConcurrentHashMap<>());

      for (FieldRegion parcel : ctx.comp().parcels()) {
         BlockPos tillable = findTillableIn(level, parcel);
         BlockPos emptyFarm = findEmptyFarmlandIn(level, parcel);

         // Livestock narrative: for every TOOL_FETCH task whose tool we
         // lack, if there's at least one adult of the target species on
         // this parcel, write an "I need <tool>" memory so the LLM can
         // weave it into dialogue. Sheep/shears was the original case;
         // cow/bucket and future entries piggyback the same flow.
         if (parcel.type() == FieldRegion.Type.ANIMAL) {
            for (var task : com.yucareux.townfolk.world.livestock.LivestockTasks.TOOL_FETCH) {
               if (task.hasTool(ctx.actor())) continue;
               var mn = parcel.scanMin(); var mx = parcel.scanMax();
               var aabb = new net.minecraft.world.phys.AABB(
                  mn.getX(), mn.getY(), mn.getZ(),
                  mx.getX() + 1, mx.getY() + 1, mx.getZ() + 1);
               var here = level.getEntitiesOfClass(task.targetType(), aabb,
                  a -> a.isAlive() && !a.isBaby());
               if (here.isEmpty()) continue;
               String toolName = task.toolItemId() != null
                  ? task.toolItemId().substring(task.toolItemId().indexOf(':') + 1).replace('_', ' ')
                  : "the right tool";
               String species = here.get(0).getType().getDescription().getString().toLowerCase();
               maybeWriteLack(ctx, perVillager, currentDay, task.id() + "_tool",
                  "At my " + parcel.shortLabel(ctx.town().getBlockPos())
                     + " there's a " + species + " ready for me to " + task.verb()
                     + ", but I have no " + toolName + ".");
            }
         }
         // Crop-related needs only apply to PLANT parcels. A herder
         // assigned an ANIMAL parcel that happens to contain dirt/grass
         // would otherwise be told they "need a hoe to till" — the LLM
         // then weaves crop-farming talk into dialogue for someone whose
         // job has nothing to do with crops.
         if (parcel.type() == FieldRegion.Type.PLANT) {
            if (tillable != null && !haveHoe) {
               maybeWriteLack(ctx, perVillager, currentDay, "hoe",
                  "At my " + parcel.shortLabel(ctx.town().getBlockPos())
                     + " there's good soil to till, but I have no hoe. I should ask the player.");
            }
            if (emptyFarm != null && !haveSeed) {
               maybeWriteLack(ctx, perVillager, currentDay, "seeds",
                  "At my " + parcel.shortLabel(ctx.town().getBlockPos())
                     + " the farmland sits empty — I need seeds to plant.");
            }
            if (tillable != null && haveHoe && !haveSeed) {
               maybeWriteLack(ctx, perVillager, currentDay, "seeds_for_till",
                  "At my " + parcel.shortLabel(ctx.town().getBlockPos())
                     + " I could till the soil but I have no seeds to follow up with.");
            }
         }
      }
   }

   private static void maybeWriteLack(WorkProductionService.Ctx ctx,
                                       java.util.Map<String, Long> perVillager,
                                       long currentDay, String needKey, String memoryText) {
      Long prev = perVillager.get(needKey);
      if (prev != null && prev == currentDay) return;
      perVillager.put(needKey, currentDay);
      MemoryStore.write(ctx.actor(), "need:" + needKey, currentDay, memoryText);
      VerboseLog.write("PARCEL_LACK", "actor=" + ctx.entry().name() + " need=" + needKey, memoryText);

      // Also create a tagged todo so NeedsService picks this up and actively
      // hails the player. NeedsService scans for "[need:X] " prefixed text on
      // open todos. Dedup against existing open ones to avoid pile-up.
      var comp = ctx.actor().getData(
         com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      String tag = "[need:" + needKey + "] ";
      boolean already = comp.todos().stream()
         .anyMatch(t -> t.isOpen() && t.text().startsWith(tag));
      if (already) return;
      var todo = new com.yucareux.townfolk.villager.Todo(
         java.util.UUID.randomUUID().toString(),
         tag + memoryText, "player", "open", currentDay);
      ctx.actor().setData(
         com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get(),
         comp.withAppendedTodo(todo));
   }

   /** Close any open [need:X] todo whose underlying need is now satisfied.
    *  Called once per routine tick (cheap inventory scan), so as soon as the
    *  player hands the villager the missing item, the hail stops. */
   static void closeSatisfiedNeeds(WorkProductionService.Ctx ctx) {
      var comp = ctx.actor().getData(
         com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      if (comp.todos().isEmpty()) return;
      boolean haveHoe = hasToolWithTag(ctx.actor(), net.minecraft.tags.ItemTags.HOES);
      boolean haveSeed = findSeed(ctx.actor().getInventory()) != null;
      // Crop tool needs are MOOT if the villager has no PLANT parcels —
      // a herder with only ANIMAL parcels shouldn't be hailing the
      // player about hoes / seeds (could be a leftover todo from before
      // we gated the lack-detection by parcel type, or from a parcel
      // that's since been retyped / removed).
      boolean hasPlantParcel = false;
      for (var p : comp.parcels()) {
         if (p.type() == FieldRegion.Type.PLANT) { hasPlantParcel = true; break; }
      }
      boolean changed = false;
      var next = new java.util.ArrayList<>(comp.todos());
      for (int i = 0; i < next.size(); i++) {
         var t = next.get(i);
         if (!t.isOpen()) continue;
         String tx = t.text();
         boolean isHoeTodo  = tx.startsWith("[need:hoe] ");
         boolean isSeedTodo = tx.startsWith("[need:seeds] ") || tx.startsWith("[need:seeds_for_till] ");
         if (isHoeTodo && (haveHoe || !hasPlantParcel)) {
            next.set(i, t.withStatus("done"));
            changed = true;
         } else if (isSeedTodo && (haveSeed || !hasPlantParcel)) {
            next.set(i, t.withStatus("done"));
            changed = true;
         } else if (tx.startsWith("[need:shears] ") && hasItem(ctx.actor(), "minecraft:shears")) {
            next.set(i, t.withStatus("done"));
            changed = true;
         }
      }
      if (changed) {
         ctx.actor().setData(
            com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get(),
            comp.withTodos(next));
      }
   }

   // Pasture protection used to be a runtime heuristic
   // (`parcelHasGrazingAnimals` — does an AABB scan find any grazer?) but
   // is now explicit via {@link FieldRegion.Type#ANIMAL}. The routine's
   // plant-vs-animal split (see {@link #tryAllForParcel}) makes the
   // distinction at the level it belongs: parcel intent, set by the
   // player at creation time via the Surveyor's Stake popup.

   // ───── reclaim — convert stone/sand/gravel surface blocks to dirt ─────

   /** Find a "reclaimable" surface block in the parcel — anything that
    *  isn't already farmable substrate (dirt family + farmland) and isn't
    *  a structural / living block we shouldn't smash (logs, leaves,
    *  saplings, crops, flowers, anything with a BlockEntity). Top-of-
    *  column only: must have air directly above. */
   private static BlockPos findReclaimableIn(net.minecraft.server.level.ServerLevel level, FieldRegion parcel) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos mn = parcel.scanMin(), mx = parcel.scanMax();
      for (int x = mn.getX(); x <= mx.getX(); x++) {
         for (int y = mn.getY(); y <= mx.getY(); y++) {
            for (int z = mn.getZ(); z <= mx.getZ(); z++) {
               cur.set(x, y, z);
               if (!isReclaimable(level, cur, level.getBlockState(cur))) continue;
               if (!level.getBlockState(cur.above()).isAir()) continue;
               return cur.immutable();
            }
         }
      }
      return null;
   }

   /** Reclaim any non-farmable, non-structural surface block. The farmer
    *  is preparing the parcel for farmland — anything that isn't already
    *  good soil (or about to grow into food) gets broken and replaced
    *  with dirt. Excludes the dirt family + farmland (already fine),
    *  fluids (don't churn streams into dirt), and anything alive or
    *  player-built (logs, leaves, saplings, crops, flowers, doors,
    *  chests, beds — anything with a BlockEntity or in a growth tag). */
   private static boolean isReclaimable(net.minecraft.server.level.ServerLevel level,
                                         BlockPos pos, BlockState s) {
      if (s.isAir()) return false;
      if (!s.getFluidState().isEmpty()) return false;
      // Already-good substrate: leave alone.
      if (s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.GRASS_BLOCK)
          || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH)
          || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MUD)
          || s.is(Blocks.MOSS_BLOCK)) return false;
      // Living / decorative: don't smash trees, crops, flowers, etc.
      if (s.is(net.minecraft.tags.BlockTags.LOGS)
          || s.is(net.minecraft.tags.BlockTags.LEAVES)
          || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
          || s.is(net.minecraft.tags.BlockTags.CROPS)
          || s.is(net.minecraft.tags.BlockTags.FLOWERS)
          || s.is(net.minecraft.tags.BlockTags.WOOL)) return false;
      // Small ground-cover plants (grass tufts, ferns, dead bushes,
      // snow layers). Without this branch the reclaim step would
      // destroy the tuft AND place dirt where the tuft stood — which
      // ends up ABOVE the grass_block beneath, producing the visual
      // "dirt block on top of grass" bug. The till step handles
      // these properly: it clears the tuft before placing farmland.
      if (isClearableSurfaceTuft(s)) return false;
      // Sugar cane, cactus, bamboo — tall growable plants the player
      // might be cultivating. Leave alone.
      if (s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO)
          || s.is(Blocks.BAMBOO_SAPLING)) return false;
      // Anything with a BlockEntity is player-built or interactable
      // (chest, beehive, barrel, door, bed, lectern, …) — out of bounds.
      if (level.getBlockEntity(pos) != null) return false;
      return true;
   }

   private static void enqueueReclaimAt(WorkProductionService.Ctx ctx,
                                        FieldRegion parcel, BlockPos pos) {
      var level = ctx.level();
      BlockTaskQueue.enqueue(level, ctx.actor(), new BlockTaskQueue.BlockTask(
         ctx.actor().getUUID(), pos,
         level.getGameTime() + 20L * 30, "reclaim",
         (lvl, v, p) -> {
            BlockState existing = lvl.getBlockState(p);
            String wasName = BuiltInRegistries.BLOCK.getKey(existing.getBlock()).getPath();
            // Collect drops as we destroy — gives the villager something for
            // their trouble (cobblestone from stone, etc.).
            var drops = net.minecraft.world.level.block.Block.getDrops(
               existing, lvl, p, null, v, v.getMainHandItem());
            lvl.destroyBlock(p, false, v);
            for (ItemStack d : drops) {
               if (d.isEmpty()) continue;
               ItemStack leftover = v.getInventory().addItem(d);
               if (leftover != null && !leftover.isEmpty()) {
                  var ie = new ItemEntity(lvl, v.getX(), v.getY(), v.getZ(), leftover);
                  ie.setPickUpDelay(20);
                  lvl.addFreshEntity(ie);
               }
            }
            // Place dirt — "magic" supply for now (user said it's fine).
            lvl.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
            long day = lvl.getGameTime() / 24000L;
            String parcelTag = parcel.shortLabel(ctx.town().getBlockPos());
            MemoryStore.write(v, "work", day,
               "At my " + parcelTag + " I cleared a patch of " + wasName
                  + " and laid dirt in its place — better soil for the field.");
            return "reclaimed " + wasName + " → dirt at " + p.toShortString();
         }));
      VerboseLog.write("PARCEL_TASK", "actor=" + ctx.entry().name()
         + " parcel=" + parcel.id() + " task=reclaim pos=" + pos.toShortString(), "");
   }

   // ───── livestock helpers (action bodies live in world/livestock/*) ─────

   private static boolean hasItem(Villager actor, String itemId) {
      Item target = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(itemId));
      var inv = actor.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == target) return true;
      }
      return false;
   }

   /** Crops we'll auto-deposit when we're carrying more than the reserve.
    *  Wheat reserve is 3 (one baking batch); seed crops reserve enough for
    *  a few replants. Keys are item ids, values are "keep at least N". */
   /** Per-item deposit policy.
    *
    *  {@code keep}      — how many of the item the villager always tries to
    *                      hold onto. Anything above this is surplus.
    *  {@code triggerAt} — the inventory count at which a deposit trip
    *                      becomes worth doing. Below this we wait (avoids
    *                      walking to the barrel for 1 wheat at a time).
    *
    *  Iteration order = deposit priority. Wheat first because the user
    *  most cares about banking it; products before seeds because seeds are
    *  the working stock we WANT to keep replenishing the inventory with.
    */
   private record DepositRule(int keep, int triggerAt) {}

   private static final java.util.Map<String, DepositRule> DEPOSIT_RULES;
   static {
      java.util.Map<String, DepositRule> m = new java.util.LinkedHashMap<>();
      // RULE DESIGN: keep a meaningful gap between triggerAt and keep so
      // a single trip moves a batch worth banking, not one or two items.
      // (Previous tight rules — e.g. seeds keep=64, triggerAt=65 — fired
      // after every harvest that yielded ≥1 seed, producing the
      // "harvest one stalk, walk to barrel, repeat" loop.)

      // Wheat: deposit at every half-stack, empty the bag.
      m.put("minecraft:wheat",          new DepositRule(0, 32));
      // Bread: rare in autonomous play (no auto-bake), bank any if it appears.
      m.put("minecraft:bread",          new DepositRule(0, 4));
      // Wool: every dye colour. ~half a sheep's worth (1-3 per shear, so
      // 8 ≈ 3-4 sheep) per trip.
      for (var c : net.minecraft.world.item.DyeColor.values()) {
         m.put("minecraft:" + c.getName() + "_wool", new DepositRule(0, 8));
      }
      // Animal products. Milk is a single-bucket item — deposit every
      // bucket immediately so the cow-task gate (which skips ready cows
      // when the villager is already holding milk) clears quickly.
      m.put("minecraft:milk_bucket",    new DepositRule(0, 1));
      m.put("minecraft:egg",            new DepositRule(0, 16));
      m.put("minecraft:leather",        new DepositRule(0, 8));
      m.put("minecraft:feather",        new DepositRule(0, 16));
      m.put("minecraft:rabbit_hide",    new DepositRule(0, 8));
      // Meat-style items are bulk; bigger trigger so trips are worth it.
      m.put("minecraft:beef",           new DepositRule(0, 8));
      m.put("minecraft:porkchop",       new DepositRule(0, 8));
      m.put("minecraft:chicken",        new DepositRule(0, 8));
      m.put("minecraft:rabbit",         new DepositRule(0, 8));
      m.put("minecraft:mutton",         new DepositRule(0, 8));
      // Crops that double as their own seed — keep enough to replant a
      // patch, bank when surplus is ~half a stack so trips are worthwhile.
      m.put("minecraft:carrot",         new DepositRule(16, 48));
      m.put("minecraft:potato",         new DepositRule(16, 48));
      m.put("minecraft:beetroot",       new DepositRule(0, 32));
      // Seeds: never carry more than ~1 stack long-term. Allow up to 96
      // before a trip — that's 32 seeds of slack — so a single trip
      // banks ~32 seeds back down to a 64-stack rather than running to
      // the barrel after every harvest that yielded a single seed.
      m.put("minecraft:wheat_seeds",    new DepositRule(64, 96));
      m.put("minecraft:beetroot_seeds", new DepositRule(64, 96));
      DEPOSIT_RULES = java.util.Collections.unmodifiableMap(m);
   }

   /** If the villager is carrying more than the reserve of any depositable
    *  crop / product, fire a {@code deposit N <item>} verb. ToolDispatcher
    *  auto-routes the walk to the nearest barrel via TownTreasury. One item
    *  kind per tick — next tick handles the next surplus.
    *
    *  When {@code ignoreTrigger} is true, the per-item {@code triggerAt}
    *  threshold is bypassed — any positive surplus qualifies. Used by the
    *  inventory-pressure path so a near-full bag never sits on a small
    *  surplus waiting for the trigger. */
   private static boolean tryDepositSurplus(WorkProductionService.Ctx ctx,
                                            InventorySnapshot snap, boolean ignoreTrigger) {
      StringBuilder trace = new StringBuilder();
      for (var e : DEPOSIT_RULES.entrySet()) {
         DepositRule rule = e.getValue();
         int have = snap.countOf(e.getKey());
         if (have == 0) continue;                          // silent: don't spam zero entries
         boolean triggerOk = ignoreTrigger || have >= rule.triggerAt();
         int surplus = have - rule.keep();
         if (!triggerOk) {
            trace.append(e.getKey()).append("(have=").append(have)
                 .append(", trigger=").append(rule.triggerAt()).append(") ");
            continue;
         }
         if (surplus <= 0) {
            trace.append(e.getKey()).append("(have=").append(have)
                 .append(", keep=").append(rule.keep()).append(") ");
            continue;
         }
         Item rulItem = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(e.getKey()));
         if (rulItem == null) continue;
         var dest = com.yucareux.townfolk.town.TownTreasury.findNearestForDeposit(
            ctx.level(), ctx.actor().blockPosition(), rulItem);
         if (dest.isEmpty()) {
            VerboseLog.write("PARCEL_DEPOSIT_NO_DEST", "actor=" + ctx.entry().name()
               + " item=" + e.getKey() + " surplus=" + surplus,
               "no registered barrel accepts this item — writing [need:barrel_for_X] todo");
            writeNeedBarrelMemory(ctx, e.getKey());
            continue;             // try the next rule before giving up
         }
         String itemPath = e.getKey().substring(e.getKey().indexOf(':') + 1);
         ToolDispatcher.execute(ctx.level(), ctx.town(), ctx.actor(), ctx.entry(),
            "deposit " + surplus + " " + itemPath);
         VerboseLog.write("PARCEL_DEPOSIT", "actor=" + ctx.entry().name()
            + " item=" + itemPath + " count=" + surplus
            + " ignoreTrigger=" + ignoreTrigger,
            "dest=" + dest.get().pos().toShortString());
         return true;
      }
      if (trace.length() > 0) {
         VerboseLog.write("PARCEL_DEPOSIT_SKIP", "actor=" + ctx.entry().name()
            + " ignoreTrigger=" + ignoreTrigger,
            "no item qualified: " + trace.toString().trim());
      }
      return false;
   }

   /** Minimum free slots the villager wants to keep available for incoming
    *  drops. 1 slot of head-room is enough — most harvest drops merge
    *  into existing stacks; only a NEW item type needs a fresh slot, and
    *  the pressure path will have cleared one by the time that happens. */
   private static final int MIN_FREE_SLOTS = 1;

   /** Single-pass inventory summary built once per {@link #tryTick}. Lets
    *  pressure, deposit, and sweep all consult the same totals/free-slot
    *  view instead of each walking the bag independently. */
   private record InventorySnapshot(java.util.Map<String, Integer> totals,
                                    int freeSlots, int totalSlots) {
      int countOf(String itemId) { return totals.getOrDefault(itemId, 0); }
      boolean has(String itemId) { return countOf(itemId) > 0; }
      boolean isPressured()       { return freeSlots < MIN_FREE_SLOTS; }
   }

   /** Build the per-tick {@link InventorySnapshot} from the villager's
    *  current bag. Call AFTER {@link #compactInventory} so the slot view
    *  reflects de-duplicated stacks. */
   private static InventorySnapshot snapshot(Villager actor) {
      var inv = actor.getInventory();
      java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
      int free = 0, total = inv.getContainerSize();
      for (int i = 0; i < total; i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) { free++; continue; }
         var id = BuiltInRegistries.ITEM.getKey(s.getItem());
         if (id != null) totals.merge(id.toString(), s.getCount(), Integer::sum);
      }
      return new InventorySnapshot(totals, free, total);
   }

   /** Panic dump: bag is full, the villager goes to the nearest barrel
    *  and unloads everything that isn't a tool. Seeds get banked too —
    *  the routine will fetch a fresh stack the next time it needs to
    *  plant. User-spec'd behaviour: "remove everything except his tools."
    *
    *  Two-path optimisation:
    *   - If a storage barrel sits within 6 blocks of the villager
    *     already, fire "deposit all <item>" for EVERY non-tool item-
    *     kind in one tick — each call deposits inline (no new
    *     BlockTask), so the bag empties without any further round
    *     trips.
    *   - Otherwise fire a single "deposit all <first item>" verb. That
    *     verb's treasury routing enqueues a walk-to-barrel BlockTask;
    *     on arrival the chain listener re-fires the routine and the
    *     in-range branch above takes over to clear the rest. */
   private static boolean tryDumpMisc(WorkProductionService.Ctx ctx) {
      var inv = ctx.actor().getInventory();
      var level = ctx.level();
      BlockPos here = ctx.actor().blockPosition();

      // Collect distinct non-tool item ids currently in the bag.
      java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) continue;
         if (isTool(s)) continue;
         var id = BuiltInRegistries.ITEM.getKey(s.getItem());
         if (id != null) kinds.add(id.getPath());
      }
      if (kinds.isEmpty()) return false;

      // Are we already at a barrel? (Same 6-block radius ToolDispatcher
      // uses internally.)
      boolean inRange = nearestStorageWithin(level, here, 6) != null;

      if (inRange) {
         // In one tick: fire deposit-all for every kind. Each runs inline.
         for (String itemPath : kinds) {
            ToolDispatcher.execute(level, ctx.town(), ctx.actor(), ctx.entry(),
               "deposit all " + itemPath);
         }
         VerboseLog.write("PARCEL_DUMP", "actor=" + ctx.entry().name()
            + " kinds=" + kinds.size(), "in-range pressure dump");
         return true;
      }

      // Not in range — try each candidate kind until one has an
      // accepting registered container, walk there, the chain re-fires
      // the routine and the in-range branch above clears the rest.
      for (String first : kinds) {
         var firstItem = BuiltInRegistries.ITEM.get(
            net.minecraft.resources.ResourceLocation.parse("minecraft:" + first));
         if (firstItem == null) continue;
         var dest = com.yucareux.townfolk.town.TownTreasury.findNearestForDeposit(level, here, firstItem);
         if (dest.isEmpty()) {
            writeNeedBarrelMemory(ctx, "minecraft:" + first);
            continue;
         }
         ToolDispatcher.execute(level, ctx.town(), ctx.actor(), ctx.entry(),
            "deposit all " + first);
         VerboseLog.write("PARCEL_DUMP", "actor=" + ctx.entry().name()
            + " item=" + first, "out-of-range pressure dump — walking to barrel");
         return true;
      }
      return false;
   }

   /** Opportunistic sweep — called once per tryTick. If the villager is
    *  within 6 blocks of a storage barrel/chest/shulker, dump every
    *  inventory item that ISN'T relevant to current activity in one
    *  batched action. "Relevant to current activity" = tools + a
    *  working stack of seeds (max 64) for any crop the routine plants.
    *  Anything else (extra wheat, bread, wool, cobblestone, dirt drops,
    *  ItemPickupGoal sweepings, second seed stacks, etc.) gets deposited.
    *
    *  Importantly NOT gated on inventory pressure — runs every visit,
    *  so the bag stays trim even when half-empty. */
   private static void sweepNonEssentialIfAtBarrel(WorkProductionService.Ctx ctx,
                                                    InventorySnapshot snap) {
      var level = ctx.level();
      BlockPos here = ctx.actor().blockPosition();
      if (nearestStorageWithin(level, here, 6) == null) return;

      // CRITICAL GATE: only sweep if SOMETHING in the bag has crossed its
      // normal triggerAt threshold. Otherwise a villager whose parcel sits
      // adjacent to a barrel fires the sweep every single harvest —
      // depositing 1 wheat at a time. The sweep is "while we're here for
      // a legitimate drop-off, may as well dump everything"; it must NOT
      // be the reason for the trip.
      boolean meaningful = false;
      for (var rule : DEPOSIT_RULES.entrySet()) {
         if (snap.countOf(rule.getKey()) >= rule.getValue().triggerAt()) {
            meaningful = true;
            break;
         }
      }
      if (!meaningful) return;

      var inv = ctx.actor().getInventory();

      // Collect distinct items that aren't essential to current work.
      // We also dump SEEDS-ABOVE-64 so we never leak the work stock but
      // also never carry more than one stack.
      java.util.LinkedHashMap<String, Integer> toDump = new java.util.LinkedHashMap<>();
      java.util.Map<Item, Integer> seedSeen = new java.util.HashMap<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty()) continue;
         if (isTool(s)) continue;                       // hoes, shears, etc. — keep
         // Seeds: keep up to 64 per crop kind, dump the rest.
         if (s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof CropBlock) {
            int already = seedSeen.getOrDefault(s.getItem(), 0);
            int keep = Math.max(0, 64 - already);
            int dump = Math.max(0, s.getCount() - keep);
            seedSeen.put(s.getItem(), Math.min(64, already + s.getCount()));
            if (dump <= 0) continue;
            var id = BuiltInRegistries.ITEM.getKey(s.getItem());
            toDump.merge(id.getPath(), dump, Integer::sum);
            continue;
         }
         // Everything else: dump it all.
         var id = BuiltInRegistries.ITEM.getKey(s.getItem());
         if (id != null) toDump.merge(id.getPath(), s.getCount(), Integer::sum);
      }
      if (toDump.isEmpty()) return;

      for (var e : toDump.entrySet()) {
         ToolDispatcher.execute(level, ctx.town(), ctx.actor(), ctx.entry(),
            "deposit " + e.getValue() + " " + e.getKey());
      }
      VerboseLog.write("PARCEL_SWEEP", "actor=" + ctx.entry().name()
         + " kinds=" + toDump.size(), toDump.toString());
   }

   /** Tools the villager always keeps — never dumped, even under
    *  pressure. Everything else (including the seed stack) is fair
    *  game once the bag fills, per user spec. */
   private static boolean isTool(ItemStack s) {
      if (s.is(net.minecraft.tags.ItemTags.HOES)) return true;
      if (s.is(net.minecraft.tags.ItemTags.AXES)) return true;
      if (s.is(net.minecraft.tags.ItemTags.PICKAXES)) return true;
      if (s.is(net.minecraft.tags.ItemTags.SHOVELS)) return true;
      if (s.is(net.minecraft.tags.ItemTags.SWORDS)) return true;
      if (s.getItem() == net.minecraft.world.item.Items.SHEARS) return true;
      if (s.getItem() == net.minecraft.world.item.Items.BUCKET) return true;
      if (s.getItem() == net.minecraft.world.item.Items.WATER_BUCKET) return true;
      return false;
   }

   /** Is there a storage barrel/chest within {@code radius} blocks? Returns
    *  the position or null. Thin wrapper over {@link com.yucareux.townfolk.town.StorageIndex#nearest}. */
   private static BlockPos nearestStorageWithin(net.minecraft.server.level.ServerLevel level,
                                                 BlockPos centre, int radius) {
      var hit = com.yucareux.townfolk.town.StorageIndex.nearest(level, centre, radius);
      return hit == null ? null : hit.pos();
   }

   // ───── inv utils (duplicated from ToolDispatcher to avoid extra coupling) ─────

   private static void takeItem(SimpleContainer inv, String itemId, int count) {
      Item target = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(itemId));
      int need = count;
      for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() != target) continue;
         int take = Math.min(s.getCount(), need);
         s.shrink(take);
         need -= take;
         if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
      }
   }

   private ParcelRoutine() {}
}

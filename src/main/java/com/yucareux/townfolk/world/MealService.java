package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.NavCall;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import com.yucareux.townfolk.town.TownTreasury;
import com.yucareux.townfolk.town.VillagerEntry;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import com.yucareux.townfolk.villager.MemoryStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Stage 12b — meal dispatcher. Twice per game day (midday + evening),
 * any villager whose {@link LlmVillagerComponent#hunger} is below
 * {@link #HUNGER_TRIGGER_BELOW} pathfinds to the nearest registered
 * barrel that holds a food item, consumes one stack of it, and bumps
 * their hunger up.
 *
 * <p>v1 keeps eating simple: pull from any registered container that
 * exposes an item-handler capability (vanilla, Sophisticated Storage,
 * Create, etc., via the existing {@link com.yucareux.townfolk.town.ContainerAdapters}
 * layer), prefer higher-nutrition items first. No cooking — raw
 * meat eats fine, just less filling. The "later eat at home/tavern"
 * direction is a future stage.
 *
 * <p>State tracked in-memory only (per-villager last-eaten gametime,
 * one bit per meal window per day). Server restart re-rolls. The
 * memory of having eaten persists via {@link MemoryStore} writes.
 *
 * <h2>Food priority + nutrition</h2>
 * Ordered descending by hunger restored:
 * <pre>
 *   bread, cooked_*    +40
 *   beef, porkchop, ...+25  (raw meats)
 *   carrot, potato     +20
 *   beetroot           +15
 *   apple              +15
 *   cabbage (mod)      +15
 *   wheat              +5   (last resort)
 * </pre>
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class MealService {

   private static final int POLL_TICKS = 40;                    // 2 s

   /** Meal windows — gametime within a day. Midday brunch + evening
    *  supper. Windows are 600 ticks wide so the poll cycle catches
    *  every villager at least once. */
   private static final long MIDDAY_START = 4500L;
   private static final long MIDDAY_END   = 5100L;
   private static final long SUPPER_START = 9500L;              // just before tavern dispatch
   private static final long SUPPER_END   = 10100L;

   /** Below this hunger value, the villager will go eat at the next
    *  meal window. Above it, they skip the meal — already full. */
   private static final int HUNGER_TRIGGER_BELOW = 80;

   /** Walking speed for the food run. Slightly above leisure speed —
    *  hungry villagers move with a touch more purpose. */
   private static final double EAT_WALK_SPEED = 0.55;

   /** Distance threshold for "I'm at the barrel and can take food." */
   private static final double EAT_REACH_SQ = 9.0;               // 3 blocks

   /** Per-villager (day, mealKey) → already-eaten this window? */
   private static final Map<UUID, String> LAST_MEAL_KEY = new ConcurrentHashMap<>();

   /** Per-villager target barrel for the in-progress walk. */
   private static final Map<UUID, BlockPos> MEAL_TARGETS = new ConcurrentHashMap<>();

   /** Per-villager warning suppression for "no food in town" — write
    *  the TownLog line + memory at most once per meal window. */
   private static final Map<UUID, String> NO_FOOD_REPORTED = new ConcurrentHashMap<>();

   private MealService() {}

   /** Food priority tiers — driven by the
    *  {@link com.yucareux.townfolk.registry.TownfolkItemTags}
    *  {@code food/staple_*} tags. Tiers are tried top-down: highest-
    *  nutrition tag scanned first across every registered town barrel
    *  before moving to the next tier. Adding a new food (mod or
    *  datapack) is a JSON edit, not a code change. */
   private record FoodTier(net.minecraft.tags.TagKey<Item> tag, int nutrition) {}

   private static final java.util.List<FoodTier> FOOD_TIERS = java.util.List.of(
      new FoodTier(com.yucareux.townfolk.registry.TownfolkItemTags.FOOD_STAPLE_HIGH,  40),
      new FoodTier(com.yucareux.townfolk.registry.TownfolkItemTags.FOOD_STAPLE_MED,   20),
      new FoodTier(com.yucareux.townfolk.registry.TownfolkItemTags.FOOD_STAPLE_LOW,   15),
      new FoodTier(com.yucareux.townfolk.registry.TownfolkItemTags.FOOD_STAPLE_GRAIN,  5)
   );

   @SubscribeEvent
   public static void onTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      long now = level.getGameTime();
      if (now % POLL_TICKS != 0L) return;
      long dayTick = now % 24000L;
      long today = now / 24000L;
      String mealKey = mealKeyForTick(dayTick, today);
      if (mealKey == null) return;             // not in any meal window

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         TownData data = town.getTown();
         for (VillagerEntry entry : data.villagers()) {
            if (!entry.alive()) continue;
            if (!(level.getEntity(entry.uuid()) instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            if (comp.hunger() >= HUNGER_TRIGGER_BELOW) continue;
            String prev = LAST_MEAL_KEY.get(v.getUUID());
            if (mealKey.equals(prev)) continue;       // already ate this window

            // ── Find best food source. Iterate FOOD_PRIORITY top-down
            //    so highest-nutrition gets first dibs across town. ──
            FoodHit hit = findBestFoodInTown(level, v.blockPosition());
            if (hit == null) {
               reportNoFood(level, town, v, entry, mealKey);
               continue;
            }

            BlockPos target = hit.pos;
            MEAL_TARGETS.put(v.getUUID(), target);

            // ── If close enough, consume one item and bump hunger. ──
            double distSq = v.blockPosition().distSqr(target);
            if (distSq <= EAT_REACH_SQ) {
               consumeAndFeed(level, town, v, entry, comp, hit, mealKey);
               MEAL_TARGETS.remove(v.getUUID());
            } else if (!v.getNavigation().isInProgress()) {
               NavCall.moveTo(v, target, EAT_WALK_SPEED, "Meal.fetch");
            }
         }
      }
   }

   // ────────── Helpers ──────────

   /** Mealkey for the current tick, or null if we're between windows.
    *  The key encodes the day so the per-villager dedup map can tell
    *  "already ate midday today" from "ate midday yesterday". */
   private static String mealKeyForTick(long dayTick, long today) {
      if (dayTick >= MIDDAY_START && dayTick < MIDDAY_END) return today + ":midday";
      if (dayTick >= SUPPER_START && dayTick < SUPPER_END) return today + ":supper";
      return null;
   }

   /** Hit returned by the food search: position + the food item + the
    *  nutrition it restores. */
   private record FoodHit(BlockPos pos, Container container, String itemId, int hungerValue) {}

   private static FoodHit findBestFoodInTown(ServerLevel level, BlockPos from) {
      long perfT0 = com.yucareux.townfolk.diag.PerfLog.now();
      try {
         return findBestFoodInTownImpl(level, from);
      } finally {
         com.yucareux.townfolk.diag.PerfLog.sample("MealService.findBestFoodInTown",
            com.yucareux.townfolk.diag.PerfLog.now() - perfT0);
      }
   }

   private static FoodHit findBestFoodInTownImpl(ServerLevel level, BlockPos from) {
      // Stage 21b: tag-driven food search now consults the
      // {@link com.yucareux.townfolk.town.StorageRegistry#tilesForTag}
      // reverse index instead of walking every registered barrel ×
      // every slot. The index returns ONLY barrels currently holding
      // at least one tier-matching item — typically a tiny fraction
      // of the registered set.
      //
      // Within a matched barrel we still inner-loop slots to pull the
      // first matching ItemStack (need the count + item id), but the
      // outer scan is now O(matching barrels) not O(all barrels).
      // At 50 villagers × 50 barrels × 4 tiers × 15 polls / window
      // that's the audit-flagged 4M-op-per-meal-window drop to a
      // handful of operations per first-poll, with the rest of the
      // window short-circuiting via the memo cache (21c).
      for (FoodTier tier : FOOD_TIERS) {
         var positions = com.yucareux.townfolk.town.StorageRegistry
            .tilesForTag(level, tier.tag());
         if (positions.isEmpty()) continue;
         BlockPos best = null;
         double bestDistSq = Double.MAX_VALUE;
         for (Long packed : positions) {
            BlockPos pos = BlockPos.of(packed);
            double d = pos.distSqr(from);
            if (d < bestDistSq) { bestDistSq = d; best = pos; }
         }
         if (best == null) continue;
         Container c = com.yucareux.townfolk.town.ContainerAdapters.at(level, best);
         if (c == null) continue;
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            if (!s.is(tier.tag())) continue;
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(s.getItem());
            return new FoodHit(best, c, rl.toString(), tier.nutrition());
         }
      }
      return null;
   }

   private static void consumeAndFeed(ServerLevel level,
                                       TownSquareBlockEntity town,
                                       Villager v,
                                       VillagerEntry entry,
                                       LlmVillagerComponent comp,
                                       FoodHit hit,
                                       String mealKey) {
      Container c = hit.container;
      if (c == null) return;
      // Find the slot holding the food and decrement one.
      Item target = BuiltInRegistries.ITEM.get(ResourceLocation.parse(hit.itemId));
      if (target == null) return;
      int slot = -1;
      for (int i = 0; i < c.getContainerSize(); i++) {
         if (c.getItem(i).getItem() == target && !c.getItem(i).isEmpty()) {
            slot = i;
            break;
         }
      }
      if (slot < 0) return;                 // food was eaten by someone else mid-walk
      ItemStack one = c.removeItem(slot, 1);
      if (one.isEmpty()) return;
      c.setChanged();
      // Stage 21c: bump the storage mutation generation so the tag
      // reverse index (21a) re-checks this barrel on its next query.
      // Without this, the index would still report "barrel X has
      // tier-Y food" for the rest of the meal window after the
      // barrel's last matching stack was consumed — sending the next
      // hungry villager on a wasted walk that returns null at the
      // inner content check.
      com.yucareux.townfolk.town.StorageRegistry.bumpMutationGen(level);

      int before = comp.hunger();
      int after  = Math.min(100, before + hit.hungerValue);
      v.setData(ModRegistries.LLM_VILLAGER.get(), comp.withHunger(after));
      LAST_MEAL_KEY.put(v.getUUID(), mealKey);
      NO_FOOD_REPORTED.remove(v.getUUID());

      // Memory + log: brief, in-character.
      long day = level.getGameTime() / 24000L;
      String foodLabel = shortName(hit.itemId);
      MemoryStore.write(v, "meal", day,
         "I ate some " + foodLabel + " at the barrel by "
            + hit.pos.toShortString() + ".");
      town.getTown().log().add(level.getGameTime(), TownLog.Level.INFO,
         entry.name() + " ate " + foodLabel + " (hunger " + before + " → " + after + ").");
      VerboseLog.write("MEAL_EATEN",
         "villager=" + entry.name() + " food=" + hit.itemId
            + " hunger=" + before + "->" + after + " mealKey=" + mealKey,
         "");
   }

   private static void reportNoFood(ServerLevel level,
                                     TownSquareBlockEntity town,
                                     Villager v,
                                     VillagerEntry entry,
                                     String mealKey) {
      String prev = NO_FOOD_REPORTED.get(v.getUUID());
      if (mealKey.equals(prev)) return;     // already complained this window
      NO_FOOD_REPORTED.put(v.getUUID(), mealKey);
      long day = level.getGameTime() / 24000L;
      town.getTown().log().add(level.getGameTime(), TownLog.Level.WARN,
         entry.name() + " went hungry — no food in any barrel.");
      MemoryStore.write(v, "meal", day,
         "I couldn't find anything to eat in the town's barrels.");
      VerboseLog.write("MEAL_NO_FOOD",
         "villager=" + entry.name() + " mealKey=" + mealKey, "");
   }

   private static String shortName(String itemId) {
      int colon = itemId.indexOf(':');
      String path = colon >= 0 ? itemId.substring(colon + 1) : itemId;
      return path.replace('_', ' ');
   }
}

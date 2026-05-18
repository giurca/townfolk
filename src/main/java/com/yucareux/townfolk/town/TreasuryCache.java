package com.yucareux.townfolk.town;

import com.yucareux.townfolk.Townfolk;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Tick-scoped cache for {@link TownTreasury#totalOf}.
 *
 * <p>The livestock {@code ready()} predicates ({@link com.yucareux.townfolk.world.livestock.MilkCowTask},
 * {@link com.yucareux.townfolk.world.livestock.ShearSheepTask}) consult
 * the town's stockpile of the produced item to decide whether to cap
 * production. Without caching, every cow on a parcel walks the full
 * registered-container set every tick — a 30-cow parcel × 8 wool
 * colours × every tick was the audit's perf concern (item G).
 *
 * <p>Cache is cleared at the start of every {@link LevelTickEvent.Pre}.
 * Within a tick, the cache returns the same result for repeated
 * lookups; across ticks, contents are re-read fresh. This trades a
 * tick of staleness (≤ 50 ms) for an O(barrels × slots) saving per
 * call.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TreasuryCache {

   /** Per-level cache. Keyed by item id (interned strings work fine). */
   private static final Map<ServerLevel, Map<String, Integer>> CACHE = new HashMap<>();

   private TreasuryCache() {}

   /** Read the cached total for an item in a level. Computes on miss. */
   public static int totalOf(ServerLevel level, String itemId) {
      if (itemId == null || itemId.isBlank()) return 0;
      Map<String, Integer> levelCache = CACHE.computeIfAbsent(level, k -> new HashMap<>());
      Integer cached = levelCache.get(itemId);
      if (cached != null) return cached;
      int total = TownTreasury.totalOf(level, itemId);
      levelCache.put(itemId, total);
      return total;
   }

   @SubscribeEvent
   public static void onLevelTickPre(LevelTickEvent.Pre event) {
      if (event.getLevel() instanceof ServerLevel sl) {
         Map<String, Integer> levelCache = CACHE.get(sl);
         if (levelCache != null) levelCache.clear();
      }
   }
}

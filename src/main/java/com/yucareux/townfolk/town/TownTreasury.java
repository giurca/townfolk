package com.yucareux.townfolk.town;

import com.yucareux.townfolk.diag.VerboseLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * View over the player-configured {@link StorageRegistry}: the
 * "what does the town own" answer for the work routine and the LLM
 * prompts.
 *
 * Replaces the old auto-discovery approach. Two key differences:
 *   1. Routing only considers REGISTERED containers — a chest the
 *      player hasn't set up is invisible to villagers.
 *   2. Contents are read LIVE from each container's
 *      {@link net.minecraft.world.level.block.entity.BlockEntity}
 *      every query; nothing is snapshot-cached, so depositing /
 *      withdrawing never sees stale totals.
 */
public final class TownTreasury {

   /** Per-query result: a registered container's position, its
    *  configuration, and a snapshot of its live contents at query time. */
   public record Hit(BlockPos pos, StorageConfig config, Map<ResourceLocation, Integer> contents) {}

   /** Tag-search result: a {@link Hit} plus the specific {@link Item} that
    *  matched the tag (so the caller can withdraw by name). */
   public record TaggedHit(Hit hit, Item item) {
      public BlockPos pos() { return hit.pos(); }
   }

   // ───── live-content readers ─────

   /** Snapshot a Container's current contents into an item-id → count map.
    *  Empty stacks are skipped; multi-slot stacks of the same item are
    *  summed. Returns an empty map if the BE has gone away. */
   private static Map<ResourceLocation, Integer> liveContents(ServerLevel level, BlockPos pos) {
      var be = level.getBlockEntity(pos);
      if (!(be instanceof Container c)) return Map.of();
      LinkedHashMap<ResourceLocation, Integer> out = new LinkedHashMap<>();
      for (int i = 0; i < c.getContainerSize(); i++) {
         ItemStack s = c.getItem(i);
         if (s.isEmpty()) continue;
         ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
         out.merge(id, s.getCount(), Integer::sum);
      }
      return out;
   }

   /** Free-slot count in a Container, used by deposit routing to prefer
    *  barrels that can actually accept the deposit. */
   private static int freeSlots(ServerLevel level, BlockPos pos) {
      var be = level.getBlockEntity(pos);
      if (!(be instanceof Container c)) return 0;
      int free = 0;
      for (int i = 0; i < c.getContainerSize(); i++) {
         if (c.getItem(i).isEmpty()) free++;
      }
      return free;
   }

   // ───── totals across the town ─────

   /** Sum a single item across every registered container. Cheaper than
    *  {@link #available} when you only care about one item id —
    *  particularly hot path for {@link com.yucareux.townfolk.town.ProductionTargets#shouldProduce}
    *  which runs once per livestock-task readiness check. */
   public static int totalOf(ServerLevel level, Item item) {
      if (item == null) return 0;
      ResourceLocation needle = BuiltInRegistries.ITEM.getKey(item);
      if (needle == null) return 0;
      int sum = 0;
      for (var e : StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(e.getKey());
         var be = level.getBlockEntity(pos);
         if (!(be instanceof Container c)) continue;
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (needle.equals(id)) sum += s.getCount();
         }
      }
      return sum;
   }

   /** String-id flavour of {@link #totalOf(ServerLevel, Item)}. Returns 0
    *  for unknown item ids. */
   public static int totalOf(ServerLevel level, String itemId) {
      if (itemId == null || itemId.isBlank()) return 0;
      ResourceLocation loc = ResourceLocation.tryParse(itemId);
      if (loc == null) return 0;
      Item item = BuiltInRegistries.ITEM.get(loc);
      if (item == null) return 0;
      return totalOf(level, item);
   }

   /** Sum of every registered container's contents in the level. */
   public static Map<String, Integer> available(ServerLevel level) {
      Map<String, Integer> totals = new LinkedHashMap<>();
      for (var e : StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(e.getKey());
         for (var en : liveContents(level, pos).entrySet()) {
            totals.merge(en.getKey().toString(), en.getValue(), Integer::sum);
         }
      }
      return totals;
   }

   /** Town-wide count of a single item id across registered containers. */
   public static int countOf(ServerLevel level, String itemId) {
      ResourceLocation id = ResourceLocation.parse(itemId);
      int n = 0;
      for (var e : StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(e.getKey());
         Integer v = liveContents(level, pos).get(id);
         if (v != null) n += v;
      }
      return n;
   }

   // ───── nearest queries ─────

   /** Nearest registered container that already holds at least one of
    *  {@code item}. Used by withdraw routing. */
   public static Optional<Hit> findNearestWith(ServerLevel level, BlockPos from, Item item) {
      ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
      if (id == null) return Optional.empty();
      Hit best = null;
      long bestD = Long.MAX_VALUE;
      for (var e : StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(e.getKey());
         var contents = liveContents(level, pos);
         Integer v = contents.get(id);
         if (v == null || v <= 0) continue;
         long d = distSq(pos, from);
         if (d < bestD) { bestD = d; best = new Hit(pos, e.getValue(), contents); }
      }
      VerboseLog.write("TREASURY_FIND_WITH",
         "item=" + id + " from=" + from.toShortString()
            + " result=" + (best == null ? "null" : best.pos().toShortString()), "");
      return Optional.ofNullable(best);
   }

   /** Nearest registered container holding any item belonging to the
    *  given tag. Used by "fetch any hoe" style auto-fetch. */
   public static Optional<TaggedHit> findNearestWithTagged(ServerLevel level, BlockPos from, TagKey<Item> tag) {
      Hit best = null;
      Item bestItem = null;
      long bestD = Long.MAX_VALUE;
      for (var e : StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(e.getKey());
         long d = distSq(pos, from);
         if (d >= bestD) continue;                  // can't possibly beat the current best
         var contents = liveContents(level, pos);
         for (var en : contents.entrySet()) {
            if (en.getValue() <= 0) continue;
            Item it = BuiltInRegistries.ITEM.get(en.getKey());
            if (it == null) continue;
            ItemStack probe = new ItemStack(it);
            if (!probe.is(tag)) continue;
            bestD = d;
            best = new Hit(pos, e.getValue(), contents);
            bestItem = it;
            break;
         }
      }
      VerboseLog.write("TREASURY_FIND_TAGGED",
         "tag=" + tag.location() + " from=" + from.toShortString()
            + " result=" + (best == null ? "null"
               : best.pos().toShortString() + " (" + bestItem + ")"), "");
      return best == null ? Optional.empty() : Optional.of(new TaggedHit(best, bestItem));
   }

   /** Adopt the nearest registered container that has both a live
    *  inventory slot AND a free filter slot (whitelist mode) onto
    *  {@code item}: add {@code item} to its whitelist, persist, return
    *  the Hit. Called as a last-resort fallback when
    *  {@link #findNearestForDeposit} returns empty — instead of
    *  letting the villager hail the player for a new barrel, the
    *  routine adapts the existing storage to what's actually being
    *  produced.
    *
    *  <p>Blacklist-mode containers are silently skipped — they already
    *  accept anything not on their reject list, so {@code findNearestForDeposit}
    *  would have picked them up. If none qualifies (full barrels, no
    *  free filter slot, container isn't a real inventory), returns empty.
    *
    *  <p>Caller is expected to log the adoption + push a TownLog entry
    *  so the player sees the autonomous change. */
   public static Optional<Hit> adoptNearestForDeposit(ServerLevel level, BlockPos from, Item item) {
      Hit best = null;
      long bestD = Long.MAX_VALUE;
      int considered = 0, candidates = 0;
      for (var e : StorageRegistry.entries(level)) {
         considered++;
         BlockPos pos = BlockPos.of(e.getKey());
         StorageConfig cfg = e.getValue();
         // Only whitelist barrels need adopting — blacklist would have
         // accepted already (and we don't want to silently mutate
         // a player's explicit blacklist either).
         if (cfg.mode() != StorageFilterMode.WHITELIST) continue;
         // Needs at least one empty filter slot to add the item to.
         int filterSpace = StorageConfig.FILTER_SLOTS - cfg.filterCount();
         if (filterSpace <= 0) continue;
         // Needs at least one free inventory slot, else "adopting" it
         // still leaves the deposit failing on the next step.
         if (freeSlots(level, pos) <= 0) continue;
         candidates++;
         long d = distSq(pos, from);
         if (d < bestD) {
            bestD = d;
            best = new Hit(pos, cfg, liveContents(level, pos));
         }
      }
      VerboseLog.write("STORAGE_ADOPT_SCAN",
         "item=" + BuiltInRegistries.ITEM.getKey(item) + " from=" + from.toShortString()
            + " considered=" + considered + " candidates=" + candidates
            + " pick=" + (best == null ? "null" : best.pos().toShortString()), "");
      if (best == null) return Optional.empty();
      // Mutate the config in place — find the first empty filter slot
      // and stamp the item into it.
      for (int i = 0; i < StorageConfig.FILTER_SLOTS; i++) {
         if (best.config().filter().get(i).isEmpty()) {
            best.config().setFilterSlot(i, new ItemStack(item, 1));
            break;
         }
      }
      // Persist the mutation. StorageRegistry's setDirty is implicit
      // in put — re-putting the same cfg flips it.
      StorageRegistry.put(level, best.pos(), best.config());
      VerboseLog.write("STORAGE_AUTO_ACCEPT",
         "item=" + BuiltInRegistries.ITEM.getKey(item) + " pos=" + best.pos().toShortString()
            + " filterCount=" + best.config().filterCount(),
         "auto-added to whitelist so deposit can land");
      return Optional.of(best);
   }

   /** Nearest registered container whose filter ACCEPTS {@code item} AND
    *  has free space. Falls back to "accepts but full" if no slot is
    *  open, with a log warning. Returns empty when no registered
    *  container accepts the item at all — that's the
    *  {@code [need:barrel_for_X]} complaint case. */
   public static Optional<Hit> findNearestForDeposit(ServerLevel level, BlockPos from, Item item) {
      ItemStack probe = new ItemStack(item);
      Hit bestAccepting = null, bestFree = null;
      long bestAcceptD = Long.MAX_VALUE, bestFreeD = Long.MAX_VALUE;
      int considered = 0, accepting = 0, withSpace = 0;
      for (var e : StorageRegistry.entries(level)) {
         considered++;
         BlockPos pos = BlockPos.of(e.getKey());
         StorageConfig cfg = e.getValue();
         if (!cfg.acceptsDeposit(probe)) continue;
         accepting++;
         long d = distSq(pos, from);
         if (d < bestAcceptD) {
            bestAcceptD = d;
            bestAccepting = new Hit(pos, cfg, liveContents(level, pos));
         }
         if (freeSlots(level, pos) > 0 && d < bestFreeD) {
            bestFreeD = d;
            bestFree = new Hit(pos, cfg, liveContents(level, pos));
            withSpace++;
         }
      }
      Hit pick = bestFree != null ? bestFree : bestAccepting;
      VerboseLog.write("TREASURY_FIND_DEPOSIT",
         "item=" + BuiltInRegistries.ITEM.getKey(item) + " from=" + from.toShortString()
            + " considered=" + considered + " accepting=" + accepting
            + " withSpace=" + withSpace
            + " pick=" + (pick == null ? "null" : pick.pos().toShortString()
               + " (" + (bestFree != null ? "free" : "fullbutaccepts") + ")"), "");
      return Optional.ofNullable(pick);
   }

   // ───── prompt summary ─────

   public static String summary(ServerLevel level, int maxRows) {
      Map<String, Integer> totals = available(level);
      if (totals.isEmpty()) return "";
      List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
      sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < Math.min(maxRows, sorted.size()); i++) {
         var en = sorted.get(i);
         if (i > 0) sb.append(", ");
         sb.append(en.getValue()).append("× ").append(shortName(en.getKey()));
      }
      return sb.toString();
   }

   private static String shortName(String itemId) {
      int colon = itemId.indexOf(':');
      return (colon < 0 ? itemId : itemId.substring(colon + 1)).replace('_', ' ');
   }

   private static long distSq(BlockPos a, BlockPos b) {
      long dx = a.getX() - b.getX();
      long dy = a.getY() - b.getY();
      long dz = a.getZ() - b.getZ();
      return dx * dx + dy * dy + dz * dz;
   }

   private TownTreasury() {}
}

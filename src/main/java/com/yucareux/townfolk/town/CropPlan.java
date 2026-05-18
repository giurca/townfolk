package com.yucareux.townfolk.town;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/**
 * The player-set crop plan for a single PLANT parcel. A list of
 * {@code (seed item id, weight)} entries: weights are relative, not
 * absolute. So {@code [wheat:3, carrots:2, beetroots:1]} means
 * "roughly 50% wheat, 33% carrots, 17% beetroots" regardless of how
 * many tiles the parcel ends up with after the villager places water.
 *
 * <h2>Why weights, not counts or percentages</h2>
 * Counts force the player to predict post-water tile budget — they
 * can't. Percentages introduce rounding the moment the tile count
 * isn't divisible by the resolution. Weights sidestep both: a
 * deficit-tracking planter (see
 * {@code ParcelRoutine#pickPlanAwareSeed}) converges any field shape
 * toward the target ratio purely by always planting the under-served
 * crop next.
 *
 * <h2>Default fallback</h2>
 * Brand-new parcels (and any saved data missing a plan entry) get
 * {@link #DEFAULT} — wheat at weight 1. So nothing breaks for parcels
 * created before this system existed.
 *
 * <h2>Cap</h2>
 * {@link #MAX_ENTRIES} caps the row count so the UI + the per-tile
 * deficit loop stay cheap.
 */
public record CropPlan(List<Entry> entries) {

   public record Entry(String seedItemId, int weight) {
      public Entry { weight = Math.max(0, Math.min(10, weight)); }
      public Item resolveItem() {
         var loc = ResourceLocation.tryParse(seedItemId);
         return loc == null ? null : BuiltInRegistries.ITEM.get(loc);
      }
   }

   public static final int MAX_ENTRIES = 6;

   /** Default plan for parcels that don't have an explicit one set.
    *  Matches the historical "wheat only" behaviour so old parcels
    *  keep working without migration. */
   public static final CropPlan DEFAULT = new CropPlan(List.of(
      new Entry("minecraft:wheat_seeds", 1)));

   public CropPlan {
      // Defensive copy — caller can't mutate after construction.
      entries = entries == null
         ? List.of()
         : Collections.unmodifiableList(new ArrayList<>(entries));
   }

   /** Sum of weights of all non-empty entries. Zero when the plan
    *  has no active rows (UI dropped them all). */
   public int totalWeight() {
      int sum = 0;
      for (var e : entries) {
         if (e.weight > 0 && e.seedItemId != null && !e.seedItemId.isBlank()) {
            sum += e.weight;
         }
      }
      return sum;
   }

   /** True if at least one row has a non-empty item id + positive weight. */
   public boolean hasAnyActive() {
      for (var e : entries) {
         if (e.weight > 0 && e.seedItemId != null && !e.seedItemId.isBlank()) return true;
      }
      return false;
   }

   /** Sanitised view used by the routine: drops empty / zero-weight
    *  rows so target-share math doesn't divide by junk. */
   public List<Entry> activeEntries() {
      List<Entry> out = new ArrayList<>();
      for (var e : entries) {
         if (e.weight > 0 && e.seedItemId != null && !e.seedItemId.isBlank()) out.add(e);
      }
      return out;
   }

   // ───── crop-block ↔ seed-item bridging ─────

   /** Map a {@link CropBlock} (what's growing on the parcel) back to the
    *  seed item id we'd use to plant more of it.
    *
    *  Auto-discovers every {@code BlockItem → CropBlock} pair in the
    *  ITEM registry on first access. So vanilla crops (wheat, carrots,
    *  potato, beetroot) AND modded crops that extend {@link CropBlock}
    *  with a corresponding seed {@link BlockItem} — Farmer's Delight's
    *  cabbages/onions/tomatoes/rice, Croptopia, etc. — work
    *  automatically without needing per-mod code. Crops that don't
    *  follow that pattern (sugarcane, melons via stems, cocoa, sweet
    *  berries, nether wart, modded crops with custom block hierarchies)
    *  return null and don't participate in deficit tracking.
    *
    *  Cached so the registry scan happens once. Registry contents are
    *  immutable post-loading, so the cache stays valid for the life of
    *  the process. */
   public static String seedItemIdFor(Block cropBlock) {
      if (cropBlock == null) return null;
      Map<Block, String> map = SEED_FOR_CROP_BLOCK;
      if (map == null) {
         map = buildSeedForCropBlockMap();
         SEED_FOR_CROP_BLOCK = map;
      }
      return map.get(cropBlock);
   }

   private static volatile Map<Block, String> SEED_FOR_CROP_BLOCK;

   private static Map<Block, String> buildSeedForCropBlockMap() {
      Map<Block, String> out = new java.util.HashMap<>();
      for (Item item : BuiltInRegistries.ITEM) {
         if (!(item instanceof BlockItem bi)) continue;
         Block block = bi.getBlock();
         if (!(block instanceof CropBlock)) continue;
         var key = BuiltInRegistries.ITEM.getKey(item);
         if (key == null) continue;
         // First-write wins. Most mods follow the one-seed-per-crop
         // convention, but if two seed items both place the same crop,
         // we keep whichever was registered first (likely the canonical
         // one).
         out.putIfAbsent(block, key.toString());
      }
      // Hard-code the four vanilla mappings as a defensive override in
      // case any of them are ever overshadowed by mod registration
      // order — these MUST resolve to the vanilla seed.
      out.put(Blocks.WHEAT,     "minecraft:wheat_seeds");
      out.put(Blocks.CARROTS,   "minecraft:carrot");
      out.put(Blocks.POTATOES,  "minecraft:potato");
      out.put(Blocks.BEETROOTS, "minecraft:beetroot_seeds");
      return java.util.Map.copyOf(out);
   }

   /** Inverse: given a seed item, return the {@link CropBlock} it would
    *  place. Returns null if the item isn't a crop seed. */
   public static CropBlock cropBlockFor(Item seedItem) {
      if (seedItem instanceof BlockItem bi && bi.getBlock() instanceof CropBlock cb) {
         return cb;
      }
      return null;
   }

   /** True if the item can be planted via {@code BlockItem → CropBlock}.
    *  Used at every plan-input entry point (menu click, shift-click,
    *  JEI / EMI ghost drop) to refuse non-seed items before they make
    *  it into the plan. */
   public static boolean isPlantableSeed(Item item) {
      return item instanceof BlockItem bi && bi.getBlock() instanceof CropBlock;
   }

   // ───── persistence ─────

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      ListTag list = new ListTag();
      for (Entry e : entries) {
         if (e.seedItemId == null || e.seedItemId.isBlank()) continue;
         CompoundTag ec = new CompoundTag();
         ec.putString("seed", e.seedItemId);
         ec.putInt("w", e.weight);
         list.add(ec);
      }
      tag.put("entries", list);
      return tag;
   }

   public static CropPlan load(CompoundTag tag) {
      if (tag == null || !tag.contains("entries")) return DEFAULT;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      List<Entry> out = new ArrayList<>(list.size());
      for (int i = 0; i < list.size(); i++) {
         CompoundTag ec = list.getCompound(i);
         out.add(new Entry(ec.getString("seed"), ec.getInt("w")));
      }
      if (out.isEmpty()) return DEFAULT;
      return new CropPlan(out);
   }

   /** Convenience: build a "preview" map of seed id → fractional share
    *  the plan would target. Useful for the screen's "Preview: 50% wheat,
    *  33% carrots" line. */
   public Map<String, Double> targetShares() {
      Map<String, Double> out = new LinkedHashMap<>();
      int total = totalWeight();
      if (total <= 0) return out;
      for (Entry e : activeEntries()) {
         out.put(e.seedItemId, e.weight / (double) total);
      }
      return out;
   }
}

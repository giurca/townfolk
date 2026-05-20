package com.yucareux.townfolk.registry;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Centralised registry of {@link TagKey} handles for the
 * {@code townfolk:} item tags used by the work / food / deposit
 * routing systems.
 *
 * <p>Membership of every tag is declared in
 * {@code data/townfolk/tags/item/}. Any modded item can join a
 * category via a datapack tag override, so the runtime side has
 * to know NOTHING about specific item ids — it only knows
 * "this item is food/staple_high" or "this item is a bulk grain
 * we bank at 32-stack."
 *
 * <p>If a player wants Klaus to start eating Farmer's Delight
 * tomatoes, they drop a JSON file into a datapack and reload.
 * No code change.
 */
public final class TownfolkItemTags {

   private TownfolkItemTags() {}

   private static TagKey<Item> tag(String path) {
      return TagKey.create(Registries.ITEM,
         ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, path));
   }

   // ─────────────────── Food (eaten by villagers) ───────────────────
   //
   // Tiered by nutrition. The MealService iterates the tiers
   // top-down so the highest-nutrition food gets first dibs across
   // every town barrel.

   /** Bread, cooked meats, anything filling. +40 hunger per stack. */
   public static final TagKey<Item> FOOD_STAPLE_HIGH = tag("food/staple_high");
   /** Raw meats, root vegetables. +20 hunger. */
   public static final TagKey<Item> FOOD_STAPLE_MED  = tag("food/staple_med");
   /** Fruits, beetroots, ad-hoc snacks. +15 hunger. */
   public static final TagKey<Item> FOOD_STAPLE_LOW  = tag("food/staple_low");
   /** Wheat / raw grain. Last resort food, +5 hunger. */
   public static final TagKey<Item> FOOD_STAPLE_GRAIN = tag("food/staple_grain");

   // ─────────────────── Banking (deposit rules) ───────────────────
   //
   // Each bank tag pairs with hardcoded keep / triggerAt numbers in
   // ParcelRoutine.DEPOSIT_RULES so the rule's INTENT lives in code
   // but the MEMBERSHIP is data-driven.

   /** Animal products — bank immediately (milk, leather, eggs, meats).
    *  keep=0, triggerAt=1. */
   public static final TagKey<Item> BANK_ANIMAL_PRODUCT_IMMEDIATE =
      tag("bank/animal_product_immediate");
   /** Animal products that drop in stacks but aren't urgent.
    *  keep=0, triggerAt=8. */
   public static final TagKey<Item> BANK_ANIMAL_PRODUCT_BULK =
      tag("bank/animal_product_bulk");
   /** Wool of any colour. keep=0, triggerAt=8. */
   public static final TagKey<Item> BANK_WOOL = tag("bank/wool");
   /** Seeds we keep enough of to replant — keep=64, triggerAt=96. */
   public static final TagKey<Item> BANK_SEEDS_BULK = tag("bank/seeds_bulk");
   /** Crops that double as their own seed (carrot, potato). Keep
    *  a small replant reserve; bank surplus. keep=16, triggerAt=48. */
   public static final TagKey<Item> BANK_CROPS_SEED_DOUBLING =
      tag("bank/crops_seed_doubling");
   /** Bulk grain (wheat, beetroot). keep=0, triggerAt=32. */
   public static final TagKey<Item> BANK_GRAIN = tag("bank/grain");

   // ─────────────────── Tools ───────────────────

   /** Empty buckets the milk-cow task pulls from inventory. Forge's
    *  {@code c:buckets/empty} is the canonical tag where it exists;
    *  Townfolk's own tag here adds {@code minecraft:bucket} and
    *  anything else that should count as "a thing the villager can
    *  milk a cow into." */
   public static final TagKey<Item> TOOL_MILK_BUCKET = tag("tool/milk_bucket");
}

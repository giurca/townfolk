package com.yucareux.townfolk.registry;

import com.yucareux.townfolk.Townfolk;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;

/**
 * Tag handles for {@code townfolk:fluid/*} categories. Stage 23a.
 *
 * <p>Same datapack-driven pattern as {@link TownfolkItemTags} — the
 * tag membership lives in {@code data/townfolk/tags/fluid/} JSON
 * files so modded fluids (Create's chocolate, Mekanism's brine,
 * Farmer's Delight's milk, etc.) join via a JSON override rather
 * than a code change.
 *
 * <p>The LLM grammar uses these category names verbatim in the
 * fluid verbs:
 * <pre>{@code
 *   [ACTION: fill 1000 water in irrigation_tank]
 *   [ACTION: drain lava from forge_tank]
 *   [ACTION: pour honey]   // narrative emote
 * }</pre>
 * Tag membership lets the LLM say "water" without caring whether
 * the tank holds vanilla {@code minecraft:water} or a moded
 * variant like {@code create:water} (these don't actually differ —
 * Create uses vanilla fluids — but the abstraction stays consistent
 * with the item-tag system).
 */
public final class TownfolkFluidTags {

   private TownfolkFluidTags() {}

   private static TagKey<Fluid> tag(String path) {
      return TagKey.create(Registries.FLUID,
         ResourceLocation.fromNamespaceAndPath(Townfolk.MODID, path));
   }

   /** Plain water + anything the player wants treated as water-like
    *  for irrigation / drinking purposes. */
   public static final TagKey<Fluid> FLUID_WATER = tag("fluid/water");

   /** Lava + magma-like fluids that the LLM should treat as
    *  dangerous / fuel-grade. */
   public static final TagKey<Fluid> FLUID_LAVA = tag("fluid/lava");

   /** Edible / brewed fluids — milk, honey, beer, Create's chocolate.
    *  Used by future tavern/brewery flavour systems. */
   public static final TagKey<Fluid> FLUID_DRINKABLE = tag("fluid/drinkable");

   /** Catch-all for any registered fluid that doesn't fit the above —
    *  oil, magma, Create's potion, Mekanism's gases-as-fluids. The
    *  LLM still gets a generic verb against these via fluid id. */
   public static final TagKey<Fluid> FLUID_INDUSTRIAL = tag("fluid/industrial");
}

package com.yucareux.townfolk.trade;

import java.util.List;

/**
 * Kinds of travelling visitor who might leave a trade offer at a town's
 * Trade Post. Each archetype has its own weighted preference list — a
 * Butcher wants meat / bread, a Wizard wants redstone / ender pearls,
 * etc.
 *
 * <p>Each archetype's {@link #preferredItems()} is the SUPER-set the
 * trade generator may pick from. The trade generator additionally
 * filters by the town's "discovered items" (everything the town has
 * ever stockpiled), so a brand-new town only sees archetypes asking
 * for the few items it actually produces.
 *
 * <p>All item ids are vanilla Minecraft baseline — no mod
 * dependencies. If/when we add mod-item support, the per-archetype
 * pools become data-driven.
 *
 * <p>{@link #displayName()} is what shows up in the trade card flavor.
 */
public enum VisitorArchetype {
   BUTCHER("Butcher", List.of(
      "minecraft:beef", "minecraft:cooked_beef",
      "minecraft:porkchop", "minecraft:cooked_porkchop",
      "minecraft:mutton", "minecraft:cooked_mutton",
      "minecraft:chicken", "minecraft:cooked_chicken",
      "minecraft:leather",
      "minecraft:bread", "minecraft:wheat"
   )),
   SAILOR("Sailor", List.of(
      "minecraft:white_wool", "minecraft:gray_wool", "minecraft:black_wool",
      "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
      "minecraft:dried_kelp", "minecraft:melon_slice",
      "minecraft:string", "minecraft:cod", "minecraft:cooked_cod"
   )),
   WIZARD("Wandering Wizard", List.of(
      "minecraft:redstone", "minecraft:glowstone_dust",
      "minecraft:ender_pearl", "minecraft:blaze_powder",
      "minecraft:gunpowder", "minecraft:lapis_lazuli",
      "minecraft:sugar", "minecraft:nether_wart"
   )),
   NOBLE("Visiting Noble", List.of(
      "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
      "minecraft:red_wool", "minecraft:blue_wool", "minecraft:purple_wool",
      "minecraft:glass", "minecraft:iron_axe", "minecraft:iron_sword",
      "minecraft:iron_pickaxe", "minecraft:cake"
   )),
   ROAD_CREW("Road Crew Foreman", List.of(
      "minecraft:cobblestone", "minecraft:stone", "minecraft:iron_ingot",
      "minecraft:coal", "minecraft:stick", "minecraft:sandstone",
      "minecraft:gravel", "minecraft:torch"
   ));

   private final String displayName;
   private final List<String> preferredItems;

   VisitorArchetype(String displayName, List<String> preferredItems) {
      this.displayName = displayName;
      this.preferredItems = preferredItems;
   }

   public String displayName() { return displayName; }
   public List<String> preferredItems() { return preferredItems; }

   /** Tolerant {@link #valueOf}: returns {@code null} for unknown names. */
   public static VisitorArchetype safeFromName(String name) {
      if (name == null) return null;
      for (VisitorArchetype a : values()) if (a.name().equals(name)) return a;
      return null;
   }
}

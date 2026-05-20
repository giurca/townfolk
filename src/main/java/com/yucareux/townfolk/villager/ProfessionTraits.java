package com.yucareux.townfolk.villager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Single source of truth for everything we want to know about a
 * villager profession. Replaces the per-file lookup tables
 * (NeedsService block→profession, TownAdminScreen icon switch,
 * MealService preferred-food guesses, etc.) that used to drift.
 *
 * <p>Adding a new profession (Tinkerer, Miller, etc.) is one entry
 * in {@link #REGISTRY} — every system that branches on profession
 * picks the new trait up automatically.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@link #id} is the lowercase resource-path used by the
 *       vanilla {@code VillagerProfession} registry (e.g.
 *       {@code "farmer"}, {@code "none"}). Network payloads + UI
 *       filter chips use this string.
 *   <li>{@link #workstation} is the block whose presence claims
 *       this profession (vanilla AI rule). {@code Blocks.AIR}
 *       means "no workstation" — the {@code NONE} / unemployed
 *       case.
 *   <li>{@link #tileIcon} is the {@link Item} rendered on the
 *       villager-grid tile. Chosen for instant readability — a
 *       farmer tile shows wheat, not a spawn egg.
 * </ul>
 *
 * <p>The {@link #find} accessors tolerate unknown profession ids
 * by returning the {@link #UNEMPLOYED} fallback — never null. So
 * callers can write {@code traits.tileIcon()} without null-checks.
 */
public final class ProfessionTraits {

   public final String id;
   public final VillagerProfession vanilla;
   public final Block workstation;
   public final Item tileIcon;

   private ProfessionTraits(String id, VillagerProfession vanilla, Block workstation, Item tileIcon) {
      this.id = id;
      this.vanilla = vanilla;
      this.workstation = workstation;
      this.tileIcon = tileIcon;
   }

   public String id()                  { return id; }
   public VillagerProfession vanilla() { return vanilla; }
   public Block workstation()          { return workstation; }
   public Item tileIcon()              { return tileIcon; }

   /** Stack form of {@link #tileIcon} — cached lazily by callers as
    *  needed. Fresh stack every call since {@link ItemStack} is
    *  mutable. */
   public ItemStack tileIconStack() { return new ItemStack(tileIcon); }

   // ───────── Registry ─────────

   private static final Map<String, ProfessionTraits> BY_ID = new LinkedHashMap<>();
   private static final Map<Block, ProfessionTraits> BY_WORKSTATION = new LinkedHashMap<>();

   /** Fallback returned by {@link #find} when the id is unknown.
    *  Also the "no profession" entry. */
   public static final ProfessionTraits UNEMPLOYED = register(
      "none", VillagerProfession.NONE, Blocks.AIR, Items.VILLAGER_SPAWN_EGG);

   public static final ProfessionTraits FARMER = register(
      "farmer", VillagerProfession.FARMER, Blocks.COMPOSTER, Items.WHEAT);
   public static final ProfessionTraits SHEPHERD = register(
      "shepherd", VillagerProfession.SHEPHERD, Blocks.LOOM, Items.SHEARS);
   public static final ProfessionTraits BUTCHER = register(
      "butcher", VillagerProfession.BUTCHER, Blocks.SMOKER, Items.COOKED_BEEF);
   public static final ProfessionTraits MASON = register(
      "mason", VillagerProfession.MASON, Blocks.STONECUTTER, Items.SMOOTH_STONE);
   public static final ProfessionTraits LIBRARIAN = register(
      "librarian", VillagerProfession.LIBRARIAN, Blocks.LECTERN, Items.ENCHANTED_BOOK);
   public static final ProfessionTraits CARTOGRAPHER = register(
      "cartographer", VillagerProfession.CARTOGRAPHER, Blocks.CARTOGRAPHY_TABLE, Items.FILLED_MAP);
   public static final ProfessionTraits FISHERMAN = register(
      "fisherman", VillagerProfession.FISHERMAN, Blocks.BARREL, Items.FISHING_ROD);
   public static final ProfessionTraits FLETCHER = register(
      "fletcher", VillagerProfession.FLETCHER, Blocks.FLETCHING_TABLE, Items.BOW);
   public static final ProfessionTraits TOOLSMITH = register(
      "toolsmith", VillagerProfession.TOOLSMITH, Blocks.SMITHING_TABLE, Items.IRON_PICKAXE);
   public static final ProfessionTraits WEAPONSMITH = register(
      "weaponsmith", VillagerProfession.WEAPONSMITH, Blocks.GRINDSTONE, Items.IRON_SWORD);
   public static final ProfessionTraits ARMORER = register(
      "armorer", VillagerProfession.ARMORER, Blocks.BLAST_FURNACE, Items.IRON_CHESTPLATE);
   public static final ProfessionTraits LEATHERWORKER = register(
      "leatherworker", VillagerProfession.LEATHERWORKER, Blocks.CAULDRON, Items.LEATHER);
   public static final ProfessionTraits CLERIC = register(
      "cleric", VillagerProfession.CLERIC, Blocks.BREWING_STAND, Items.BREWING_STAND);
   public static final ProfessionTraits NITWIT = register(
      "nitwit", VillagerProfession.NITWIT, Blocks.AIR, Items.POPPY);

   private static ProfessionTraits register(String id, VillagerProfession vanilla,
                                             Block workstation, Item icon) {
      ProfessionTraits t = new ProfessionTraits(id, vanilla, workstation, icon);
      BY_ID.put(id, t);
      if (workstation != Blocks.AIR) BY_WORKSTATION.put(workstation, t);
      return t;
   }

   /** Lookup by string id. Returns {@link #UNEMPLOYED} on unknown id
    *  so callers never need a null-check. */
   public static ProfessionTraits find(String id) {
      if (id == null) return UNEMPLOYED;
      ProfessionTraits t = BY_ID.get(id.toLowerCase(java.util.Locale.ROOT));
      return t == null ? UNEMPLOYED : t;
   }

   /** Lookup by workstation block. Returns empty on no-match — the
    *  caller decides whether "no workstation" should fall back to
    *  unemployed or do nothing. */
   public static Optional<ProfessionTraits> findByWorkstation(Block b) {
      if (b == null) return Optional.empty();
      return Optional.ofNullable(BY_WORKSTATION.get(b));
   }

   /** All registered traits in declaration order — useful for the
    *  Villagers-tab filter chip row + per-profession iteration. */
   public static List<ProfessionTraits> all() {
      return List.copyOf(BY_ID.values());
   }

   /** Ids in declaration order — used by the Villagers tab filter
    *  chips. Skips {@code "none"} since the Villagers tab already
    *  has a dedicated "all" / "none" pair in its chip row. */
   public static List<String> filterChipIds() {
      List<String> out = new java.util.ArrayList<>();
      out.add("all");
      // Only the common gameplay-visible professions on the chip
      // row — the full registry has 14 entries which would overflow
      // the row. Tweak this list if more professions become reachable.
      out.add("farmer");
      out.add("shepherd");
      out.add("butcher");
      out.add("mason");
      out.add("none");
      return out;
   }

}

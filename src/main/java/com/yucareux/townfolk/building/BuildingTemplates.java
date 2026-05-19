package com.yucareux.townfolk.building;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Static registry of {@link BuildingTemplate}s known to the mod.
 *
 * <p>Population happens lazily on first access — at the time of
 * registration the Blocks class fields may not be initialized yet
 * (mod load ordering), so we wait until something asks. After
 * initialization, the list is immutable.
 *
 * <p>Adding a new building type = add one constant + one entry in
 * {@link #buildAll()}. Recognition + persistence + lifecycle hooks
 * all work off this single declarative list.
 */
public final class BuildingTemplates {

   private BuildingTemplates() {}

   public static final String HOME      = "home";
   public static final String TOWN_HALL = "town_hall";

   private static volatile List<BuildingTemplate> ALL;

   /** Resolve once, cache forever. Block constants may not be ready
    *  at class-init time. */
   public static List<BuildingTemplate> all() {
      List<BuildingTemplate> local = ALL;
      if (local != null) return local;
      synchronized (BuildingTemplates.class) {
         if (ALL == null) ALL = buildAll();
         return ALL;
      }
   }

   /** Find the template whose marker list matches the given block.
    *  Returns the first hit — if you ever overlap markers across
    *  templates, the order in {@link #buildAll} wins. */
   public static Optional<BuildingTemplate> forMarker(Block block) {
      if (block == null) return Optional.empty();
      for (BuildingTemplate t : all()) {
         if (t.isMarker(block)) return Optional.of(t);
      }
      return Optional.empty();
   }

   public static Optional<BuildingTemplate> byId(String id) {
      for (BuildingTemplate t : all()) {
         if (t.id().equals(id)) return Optional.of(t);
      }
      return Optional.empty();
   }

   private static List<BuildingTemplate> buildAll() {
      List<BuildingTemplate> out = new ArrayList<>();

      // ── Home ──
      // Any colour of bed counts. One door anywhere in the wall.
      // Minimum 9 m² (a 3×3 cabin clears the bar). The bed itself
      // is NOT in requiredFurniture — the marker is implicit (you
      // can't place a building without its marker block).
      List<Block> beds = List.of(
         Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.MAGENTA_BED,
         Blocks.LIGHT_BLUE_BED, Blocks.YELLOW_BED, Blocks.LIME_BED,
         Blocks.PINK_BED, Blocks.GRAY_BED, Blocks.LIGHT_GRAY_BED,
         Blocks.CYAN_BED, Blocks.PURPLE_BED, Blocks.BLUE_BED,
         Blocks.BROWN_BED, Blocks.GREEN_BED, Blocks.RED_BED, Blocks.BLACK_BED
      );
      out.add(new BuildingTemplate(
         HOME,
         beds,
         Map.of(),         // bed is implicit
         true,             // requires door
         9, 2,             // 9 m² floor, 2 blocks tall
         1024, 12          // sane caps
      ));

      // ── Town Hall ──
      // Marker is the Charter Stone (registered as townfolk:town_square
      // internally for save-compat; renamed at the lang layer to
      // "Charter Stone"). Requires the Charter Stone itself + a door
      // + a banner (any colour) — banners are the "official flag" of
      // the town hall and force the player to add a recognizable
      // civic decoration.
      //
      // NOTE: We resolve the Charter Stone via the namespaced ID
      // since referencing TOWNFOLK's own registry from a constant
      // initializer would trip ordering. ModRegistries.TOWN_SQUARE_BLOCK
      // is read lazily here at first-access.
      Block charterStone = com.yucareux.townfolk.registry.ModRegistries.TOWN_SQUARE_BLOCK.get();
      LinkedHashMap<Block, Integer> townHallReq = new LinkedHashMap<>();
      // Banners come in 16 colors; rather than listing 16 entries
      // separately, we check at recognition time. For the template
      // declaration we list one (white) and the recognizer's furniture
      // tally will sum across all banner colors via a custom check —
      // but for v1 simplicity we just count white banners. A
      // future refinement: a "tag-based" required-furniture entry.
      townHallReq.put(Blocks.WHITE_BANNER, 1);
      out.add(new BuildingTemplate(
         TOWN_HALL,
         List.of(charterStone),
         townHallReq,
         true,             // requires door
         16, 3,            // 16 m² floor, 3 blocks tall
         1024, 12
      ));

      return List.copyOf(out);
   }
}

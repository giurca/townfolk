package com.yucareux.townfolk.building;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Bounded flood-fill + composite-scoring building recognition.
 *
 * <p>Input: a world, a marker block position, a template, and an
 * optional override.<br>
 * Output: a {@link Result} that's either VALID (with the interior +
 * boundary sets we'll cache for later invalidation) or a failure
 * with a human-readable reason.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Find an "interior seed" — an air block adjacent to or above
 *       the marker. If none, bail (marker is buried).
 *   <li>Flood fill from the seed in all 6 cardinal directions.
 *       Passable: air, water/lava sources, replaceable plants.
 *       Stops at: anything else INCLUDING doors and trapdoors (so
 *       a wall with a door in it is correctly recognized as a wall,
 *       not as a leak).
 *   <li>Hard caps abort the fill: {@code maxVolume} air blocks visited,
 *       {@code maxHeight} vertical extent, {@code MAX_VISITS}
 *       paranoia bound. Any cap → recognition fails ("room too large /
 *       open to outside").
 *   <li>Composite scoring against the template:
 *       <ul>
 *         <li>{@code minFloorArea}: count distinct (x,z) columns in
 *             the interior set (= floor area in m²).
 *         <li>{@code minHeight}: actual height of the interior.
 *         <li>{@code requireDoor}: at least one door block in
 *             boundary.
 *         <li>{@code requiredFurniture}: each entry must meet its
 *             count across interior + boundary.
 *       </ul>
 *   <li>On success, return the full interior + boundary sets so the
 *       caller can cache them and watch for changes.
 * </ol>
 *
 * <h2>Edge-case rulings</h2>
 * <ul>
 *   <li><b>Glass</b> counts as wall (it stops flood fill via
 *       isSolid). Players intuitively expect a glass cube to be a
 *       room.
 *   <li><b>Doors and trapdoors</b> stop the fill regardless of
 *       open/closed state. The door is the seam between inside and
 *       outside; both states of door = wall.
 *   <li><b>Leaves</b> stop the fill. Treehouses with leaf-only walls
 *       are intentional but rare; we choose "leaves = wall" so a
 *       treehouse with leaf walls qualifies, not so the random
 *       canopy of an oak counts as a roof.
 *   <li><b>Slabs / stairs</b> stop the fill (they're solid). Floor
 *       counting uses the floor-column count, not strict block
 *       shapes, so slab-floored rooms still count their area
 *       correctly.
 *   <li><b>Multi-floor buildings</b> are recognized per-marker; each
 *       marker has its own flood fill scope. A house with both a
 *       bed (Home) and a lectern (Library) on different floors is
 *       two recognized buildings sharing some boundary.
 * </ul>
 *
 * <p>Performance: ~1ms for a 500-block room. Linear in volume. Hot
 * paths (block place/break events) call this at most once per
 * affected building per tick.
 */
public final class BuildingRecognizer {

   /** Absolute paranoia bound on how many block positions any one
    *  recognition pass will examine — protects against pathological
    *  worlds even if maxVolume is misconfigured. */
   private static final int MAX_VISITS = 8192;

   private BuildingRecognizer() {}

   /** Recognition outcome — either {@link Status#VALID} with payload
    *  or a failure with a reason string suitable for chat / tooltips. */
   public record Result(
      Status status,
      String reason,
      Set<BlockPos> interior,
      Set<BlockPos> boundary,
      Map<Block, Integer> furnitureCounts,
      int floorArea,
      int height
   ) {
      public boolean valid() { return status == Status.VALID; }

      static Result fail(Status status, String reason) {
         return new Result(status, reason, Set.of(), Set.of(), Map.of(), 0, 0);
      }
      static Result ok(Set<BlockPos> interior, Set<BlockPos> boundary,
                       Map<Block, Integer> furniture, int floorArea, int height) {
         return new Result(Status.VALID, "ok", interior, boundary, furniture,
            floorArea, height);
      }
   }

   public enum Status {
      VALID,
      MARKER_BURIED,        // no air adjacent to marker
      OPEN_TO_OUTSIDE,      // flood fill exceeded maxVolume
      TOO_TALL,             // exceeded maxHeight
      TOO_SMALL,            // floor area < minFloorArea
      MISSING_FURNITURE,    // requiredFurniture not satisfied
      MISSING_DOOR,         // requireDoor but no door in boundary
      INTERNAL              // unexpected — bug, should not happen
   }

   /** Run the algorithm. Pure function of world state at the moment
    *  it's called — no side effects, no registry mutation. */
   public static Result recognize(BlockGetter level, BlockPos markerPos,
                                  BuildingTemplate template, BuildingOverride override) {
      BuildingOverride effective = override == null ? BuildingOverride.NONE : override;
      int maxVol = effective.effectiveMaxVolume(template);
      int maxHgt = effective.effectiveMaxHeight(template);

      // (1) Find an interior seed adjacent to the marker.
      BlockPos seed = findInteriorSeed(level, markerPos);
      if (seed == null) {
         return Result.fail(Status.MARKER_BURIED,
            "marker is buried — no open space next to it");
      }

      // (2) Flood fill.
      Set<BlockPos> interior = new LinkedHashSet<>();
      Set<BlockPos> boundary = new LinkedHashSet<>();
      Deque<BlockPos> frontier = new ArrayDeque<>();
      frontier.add(seed);
      interior.add(seed);

      int minY = seed.getY(), maxY = seed.getY();
      int visits = 0;

      while (!frontier.isEmpty()) {
         BlockPos cur = frontier.poll();
         visits++;
         if (visits > MAX_VISITS) {
            return Result.fail(Status.OPEN_TO_OUTSIDE,
               "room exceeded the absolute cap of " + MAX_VISITS + " blocks");
         }

         for (Direction d : Direction.values()) {
            BlockPos next = cur.relative(d);
            if (interior.contains(next) || boundary.contains(next)) continue;
            if (isPassable(level, next)) {
               interior.add(next);
               minY = Math.min(minY, next.getY());
               maxY = Math.max(maxY, next.getY());
               if (interior.size() > maxVol) {
                  return Result.fail(Status.OPEN_TO_OUTSIDE,
                     "room too large — exceeds " + maxVol + " air blocks (open to outside?)");
               }
               if (maxY - minY + 1 > maxHgt) {
                  return Result.fail(Status.TOO_TALL,
                     "room too tall — exceeds " + maxHgt + " blocks of vertical extent");
               }
               frontier.add(next);
            } else {
               boundary.add(next);
            }
         }
      }

      // (3) Floor area = distinct (x,z) columns in interior.
      Set<Long> columns = new HashSet<>();
      for (BlockPos p : interior) {
         columns.add(((long) p.getX() & 0xFFFFFFFFL) << 32 | ((long) p.getZ() & 0xFFFFFFFFL));
      }
      int floorArea = columns.size();
      int height = maxY - minY + 1;

      if (floorArea < template.minFloorArea()) {
         return Result.fail(Status.TOO_SMALL,
            "floor too small — " + floorArea + " m² (need " + template.minFloorArea() + ")");
      }
      if (height < template.minHeight()) {
         return Result.fail(Status.TOO_SMALL,
            "ceiling too low — " + height + " blocks (need " + template.minHeight() + ")");
      }

      // (4) Tally furniture across interior + boundary.
      Map<Block, Integer> furniture = new HashMap<>();
      for (BlockPos p : interior) {
         Block b = level.getBlockState(p).getBlock();
         furniture.merge(b, 1, Integer::sum);
      }
      for (BlockPos p : boundary) {
         Block b = level.getBlockState(p).getBlock();
         furniture.merge(b, 1, Integer::sum);
      }

      // (5) Door check.
      if (template.requireDoor() && !hasDoorIn(level, boundary)) {
         return Result.fail(Status.MISSING_DOOR,
            "needs at least one door in the wall");
      }

      // (6) Required furniture.
      for (var req : template.requiredFurniture().entrySet()) {
         int got = furniture.getOrDefault(req.getKey(), 0);
         if (got < req.getValue()) {
            return Result.fail(Status.MISSING_FURNITURE,
               "needs " + req.getValue() + "× " + blockName(req.getKey())
                  + " (have " + got + ")");
         }
      }

      return Result.ok(interior, boundary, furniture, floorArea, height);
   }

   /** A block is "passable" (flood fill expands into it) if it's air
    *  OR a replaceable block (tall grass, snow layer, etc.) OR a
    *  fluid source. Doors/trapdoors are explicitly NOT passable —
    *  they're the seam between inside and outside, both states. */
   public static boolean isPassable(BlockGetter level, BlockPos pos) {
      BlockState s = level.getBlockState(pos);
      if (isDoorLike(s) || isTrapdoorLike(s)) return false;
      if (s.isAir()) return true;
      if (s.canBeReplaced()) return true;
      // Fluid sources (water/lava): treat as passable so fountains
      // inside rooms don't break enclosure.
      if (!s.getFluidState().isEmpty() && s.getFluidState().isSource()) return true;
      return false;
   }

   private static boolean hasDoorIn(BlockGetter level, Set<BlockPos> boundary) {
      for (BlockPos p : boundary) {
         if (isDoorLike(level.getBlockState(p))) return true;
      }
      return false;
   }

   /** Match vanilla doors, tagged doors, AND structurally-door-like
    *  mod blocks that don't follow either convention.
    *
    *  <p>Three layers of detection, falling through in order:
    *  <ol>
    *    <li>{@code instanceof DoorBlock} — vanilla and any mod that
    *        extends the base class.
    *    <li>{@code #minecraft:doors} block tag — the standard
    *        cross-mod contract.
    *    <li>Structural signature: any block with the OPEN
    *        BooleanProperty AND with "door" in its registry path.
    *        Catches mods like Dramatic Doors, which registers
    *        {@code dramaticdoors:short_oak_door} etc. into its OWN
    *        tag ({@code dramaticdoors:short_doors}) instead of the
    *        vanilla one. Trapdoors and fence gates also have OPEN
    *        but get filtered above by their dedicated instanceof
    *        checks — and "door" isn't in their registry paths
    *        either.
    *  </ol> */
   private static boolean isDoorLike(BlockState s) {
      Block b = s.getBlock();
      if (b instanceof DoorBlock) return true;
      if (b instanceof TrapDoorBlock || b instanceof FenceGateBlock) return false;
      if (s.is(BlockTags.DOORS)) return true;
      if (s.hasProperty(BlockStateProperties.OPEN)) {
         var rl = BuiltInRegistries.BLOCK.getKey(b);
         if (rl != null && pathLooksLikeDoor(rl.getPath())) return true;
      }
      return false;
   }

   /** True if the registry path looks like a real door rather than
    *  something that happens to contain "door" (e.g. "doorbell",
    *  "doorframe"). Accept ending in {@code _door}, equalling
    *  {@code door}, or starting with {@code door_} (catches
    *  Dramatic Doors' {@code short_oak_door} and {@code tall_oak_door}
    *  pattern as well as any future {@code door_X} convention). */
   private static boolean pathLooksLikeDoor(String path) {
      if (path == null || path.isEmpty()) return false;
      if (path.equals("door")) return true;
      if (path.endsWith("_door")) return true;
      if (path.startsWith("door_")) return true;
      // Dramatic Doors uses short_<wood>_door / tall_<wood>_door —
      // already matches "_door" suffix above. Add a contains check
      // anchored on "_door_" or "_door" to catch oddball patterns
      // like "iron_door_block" without matching "doorbell".
      return path.contains("_door_");
   }

   /** Same logic for trapdoors — match instanceof OR the
    *  {@code #minecraft:trapdoors} tag so mod trapdoors count too. */
   private static boolean isTrapdoorLike(BlockState s) {
      Block b = s.getBlock();
      if (b instanceof TrapDoorBlock) return true;
      return s.is(BlockTags.TRAPDOORS);
   }

   /** Look for an air block adjacent to the marker on each of the 6
    *  faces, plus the position directly above. Tries above first
    *  (most natural for floor-placed markers like Charter Stone and
    *  beds). */
   private static BlockPos findInteriorSeed(BlockGetter level, BlockPos markerPos) {
      BlockPos above = markerPos.above();
      if (isPassable(level, above)) return above;
      for (Direction d : Direction.Plane.HORIZONTAL) {
         BlockPos candidate = markerPos.relative(d);
         if (isPassable(level, candidate)) return candidate;
      }
      // The block below as a last resort (lectern with cellar etc).
      BlockPos below = markerPos.below();
      if (isPassable(level, below)) return below;
      return null;
   }

   /** Best-effort human-readable name for use in failure reasons. */
   private static String blockName(Block b) {
      String desc = b.getDescriptionId();
      // Description ids look like "block.minecraft.iron_block". Trim to
      // the tail and prettify so chat reads naturally.
      int dot = desc.lastIndexOf('.');
      String tail = dot < 0 ? desc : desc.substring(dot + 1);
      return tail.replace('_', ' ');
   }
}

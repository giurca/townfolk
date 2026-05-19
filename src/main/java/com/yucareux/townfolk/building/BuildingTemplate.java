package com.yucareux.townfolk.building;

import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;

/**
 * Declarative description of a kind of building (Home, Town Hall,
 * future Workshop / Library / Tavern / etc.). The
 * {@link BuildingRecognizer} consumes a template + a candidate marker
 * position in the world and decides whether the surrounding
 * structure qualifies.
 *
 * <p>Templates are immutable singletons declared in
 * {@link BuildingTemplates}. New building kinds = new template entry.
 *
 * @param id                identifier for logs and lookups ("home",
 *                          "town_hall", …). Stable; persisted with
 *                          recognized buildings.
 * @param markerBlocks      any block in this list triggers a
 *                          recognition attempt when placed
 * @param requiredFurniture block → minimum count. Tallied across
 *                          interior + boundary of the flood-filled
 *                          room. A bed is "furniture" even though it
 *                          sits in the boundary (occludes flood fill).
 * @param requireDoor       if true, at least one door block must be
 *                          in the boundary set. The single best
 *                          filter against caves / holes / outdoor
 *                          pockets being mis-recognized as buildings.
 * @param minFloorArea      minimum distinct (x,z) columns in the
 *                          interior set. Floor area in m².
 * @param minHeight         minimum interior vertical extent in blocks
 * @param maxVolume         hard cap on interior air-block count. If
 *                          the flood fill exceeds this, the room is
 *                          considered open to outside and recognition
 *                          fails. Overridable per-building via a
 *                          Building Permit item (Stage 10b).
 * @param maxHeight         hard cap on interior vertical extent.
 *                          Same purpose / override path as maxVolume.
 */
public record BuildingTemplate(
   String id,
   List<Block> markerBlocks,
   Map<Block, Integer> requiredFurniture,
   boolean requireDoor,
   int minFloorArea,
   int minHeight,
   int maxVolume,
   int maxHeight
) {
   public BuildingTemplate {
      if (id == null || id.isBlank()) throw new IllegalArgumentException("id must be non-blank");
      if (markerBlocks == null || markerBlocks.isEmpty())
         throw new IllegalArgumentException("markerBlocks must be non-empty");
      if (requiredFurniture == null) requiredFurniture = Map.of();
      if (minFloorArea < 1)  throw new IllegalArgumentException("minFloorArea must be >= 1");
      if (minHeight < 1)     throw new IllegalArgumentException("minHeight must be >= 1");
      if (maxVolume < minFloorArea * minHeight)
         throw new IllegalArgumentException("maxVolume must be >= minFloorArea * minHeight");
      if (maxHeight < minHeight)
         throw new IllegalArgumentException("maxHeight must be >= minHeight");
   }

   /** True iff {@code block} is a marker for this template. */
   public boolean isMarker(Block block) {
      for (Block m : markerBlocks) if (m == block) return true;
      return false;
   }
}

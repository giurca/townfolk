package com.yucareux.townfolk.building;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

/**
 * A building the recognizer has accepted, cached so we don't re-run
 * the flood fill until something in this building's footprint
 * actually changes.
 *
 * <p>Identity is the {@code markerPos}. A single block in the world
 * can only anchor one building. Different building types use
 * different marker blocks (bed → Home, Charter Stone → Town Hall),
 * so collisions across types aren't possible.
 *
 * <p>Persistence: lives inside {@link BuildingRegistry}'s SavedData.
 * The boundary + interior sets are stored as long arrays for tight
 * NBT layout — typical building has ~50 boundary blocks and ~200
 * interior, so each entry is ~2KB in NBT.
 *
 * @param markerPos          the block that anchors this building
 * @param templateId         lookup key into {@link BuildingTemplates}
 * @param override           per-building cap overrides (may be NONE)
 * @param boundary           wall/floor/ceiling block positions
 * @param interior           air block positions
 * @param floorArea          stored at recognition time for the
 *                           Overview tab
 * @param height             same
 * @param lastValidatedTick  gametime when last re-validated; used
 *                           by event hooks to deduplicate within a
 *                           single tick
 * @param active             true if last validation succeeded;
 *                           kept around as inactive for one cycle
 *                           so the player gets a chat hint instead
 *                           of a silent disappearance
 */
public record RecognizedBuilding(
   BlockPos markerPos,
   String templateId,
   BuildingOverride override,
   Set<BlockPos> boundary,
   Set<BlockPos> interior,
   int floorArea,
   int height,
   long lastValidatedTick,
   boolean active
) {
   public RecognizedBuilding {
      // Defensive copy for set safety. The recognizer hands us
      // LinkedHashSet snapshots; we want them unmodifiable so callers
      // can't accidentally mutate them after persistence.
      boundary = Set.copyOf(boundary);
      interior = Set.copyOf(interior);
   }

   public RecognizedBuilding withActive(boolean a, long tick) {
      return new RecognizedBuilding(markerPos, templateId, override,
         boundary, interior, floorArea, height, tick, a);
   }

   public RecognizedBuilding withOverride(BuildingOverride o) {
      return new RecognizedBuilding(markerPos, templateId, o,
         boundary, interior, floorArea, height, lastValidatedTick, active);
   }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putLong("marker", markerPos.asLong());
      tag.putString("template", templateId);
      tag.put("override", override.save());
      tag.put("boundary", new LongArrayTag(packPositions(boundary)));
      tag.put("interior", new LongArrayTag(packPositions(interior)));
      tag.putInt("floorArea", floorArea);
      tag.putInt("height", height);
      tag.putLong("lastValidatedTick", lastValidatedTick);
      tag.putBoolean("active", active);
      return tag;
   }

   public static RecognizedBuilding load(CompoundTag tag) {
      BlockPos marker = BlockPos.of(tag.getLong("marker"));
      String id = tag.getString("template");
      BuildingOverride ov = tag.contains("override")
         ? BuildingOverride.load(tag.getCompound("override"))
         : BuildingOverride.NONE;
      Set<BlockPos> boundary = unpackPositions(tag.getLongArray("boundary"));
      Set<BlockPos> interior = unpackPositions(tag.getLongArray("interior"));
      return new RecognizedBuilding(marker, id, ov, boundary, interior,
         tag.getInt("floorArea"), tag.getInt("height"),
         tag.getLong("lastValidatedTick"),
         tag.contains("active") ? tag.getBoolean("active") : true);
   }

   private static long[] packPositions(Set<BlockPos> positions) {
      long[] arr = new long[positions.size()];
      int i = 0;
      for (BlockPos p : positions) arr[i++] = p.asLong();
      return arr;
   }

   private static Set<BlockPos> unpackPositions(long[] arr) {
      Set<BlockPos> out = new HashSet<>(arr.length);
      for (long packed : arr) out.add(BlockPos.of(packed));
      return out;
   }

   /** True iff a block change at {@code pos} should invalidate this
    *  building. Cheap O(1) since the sets are HashSet-backed. */
   public boolean affectedBy(BlockPos pos) {
      return markerPos.equals(pos) || boundary.contains(pos) || interior.contains(pos);
   }

   // Silence unused-import warning on Tag — kept for future expansion.
   @SuppressWarnings("unused")
   private static final int NBT_TAG_LONG_ARRAY = Tag.TAG_LONG_ARRAY;
}

package com.yucareux.townfolk.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * A player-assigned parcel of land that belongs to a villager. The villager's
 * {@link com.yucareux.townfolk.world.ParcelRoutine} scans the region's
 * contents each tick and decides what to do, conditioned on the parcel's
 * {@link Type}: PLANT parcels run the till/water/plant/harvest/reclaim
 * routine; ANIMAL parcels run shear/feed/milk/etc. depending on what's
 * inside.
 *
 * Type is chosen at creation time via the Surveyor's Stake popup. It can
 * be re-bound by unbinding and rebinding; we do NOT silently swap on
 * inferred content because that breaks pasture protection (e.g. a wheat
 * field briefly visited by a wandering sheep used to trip the heuristic).
 *
 * Persisted with the villager via {@link LlmVillagerComponent#parcels()}.
 * Identified by a short random {@code id} so other systems (memories,
 * dialogue, admin UI) can refer to a specific parcel without recomputing.
 *
 * Coords are stored exactly as the player picked them; helpers normalise
 * to min/max on demand so order doesn't matter.
 */
public record FieldRegion(
   String id,
   BlockPos cornerA,
   BlockPos cornerB,
   long createdDay,
   Type type
) {

   /** Top-level parcel kind, chosen by the player at creation time. PLANT
    *  parcels grow crops; ANIMAL parcels keep livestock. Future kinds
    *  (LUMBER, MINE, …) slot in here as the routine learns to handle
    *  them. */
   public enum Type {
      PLANT,
      ANIMAL;

      /** Display label shown in the parcel popup + admin UI. */
      public String label() {
         return switch (this) {
            case PLANT  -> "Crops";
            case ANIMAL -> "Animals";
         };
      }

      public static Type fromString(String s) {
         if (s == null) return PLANT;
         try { return Type.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
         catch (IllegalArgumentException ex) { return PLANT; }
      }
   }

   /** Hard cap on parcel volume. 32×32×8 = 8192 blocks. Bigger plots
    *  conceptually graduate to "estate" later. */
   public static final int MAX_VOLUME = 8192;

   public static final Codec<FieldRegion> CODEC = RecordCodecBuilder.create(i -> i.group(
      Codec.STRING.fieldOf("id").forGetter(FieldRegion::id),
      BlockPos.CODEC.fieldOf("a").forGetter(FieldRegion::cornerA),
      BlockPos.CODEC.fieldOf("b").forGetter(FieldRegion::cornerB),
      Codec.LONG.optionalFieldOf("createdDay", 0L).forGetter(FieldRegion::createdDay),
      // Optional default keeps old saves loading — pre-type parcels become
      // PLANT, which matches the old heuristic for non-grazing fields.
      Codec.STRING.optionalFieldOf("type", "PLANT")
         .xmap(Type::fromString, Type::name)
         .forGetter(FieldRegion::type)
   ).apply(i, FieldRegion::new));

   /** Backwards-compat constructor for callers / tests that don't care
    *  about type — defaults to PLANT (the historical behaviour). */
   public FieldRegion(String id, BlockPos cornerA, BlockPos cornerB, long createdDay) {
      this(id, cornerA, cornerB, createdDay, Type.PLANT);
   }

   /** How many blocks we look BELOW the lowest picked corner when scanning
    *  / containing. Catches the case where the player picked corners on
    *  the crop tile (Y+1) instead of the farmland (Y). */
   public static final int Y_INFLATE_DOWN = 1;

   /** How many blocks we look ABOVE the highest picked corner. The whole
    *  reason this exists: a player right-clicking two ground-level dirt
    *  blocks gives a parcel with Y range [64,64]. Crops grow at Y=65,
    *  which is OUTSIDE that range — so harvest scans never find them and
    *  `contains` returns false when the villager is standing on the crop.
    *  4 blocks up is plenty for crops, sugar cane, low fruit etc. */
   public static final int Y_INFLATE_UP = 4;

   public BlockPos minCorner() {
      return new BlockPos(
         Math.min(cornerA.getX(), cornerB.getX()),
         Math.min(cornerA.getY(), cornerB.getY()),
         Math.min(cornerA.getZ(), cornerB.getZ()));
   }

   public BlockPos maxCorner() {
      return new BlockPos(
         Math.max(cornerA.getX(), cornerB.getX()),
         Math.max(cornerA.getY(), cornerB.getY()),
         Math.max(cornerA.getZ(), cornerB.getZ()));
   }

   /** Min corner of the effective scan/containment volume — same X/Z as
    *  {@link #minCorner()} but Y dropped by {@link #Y_INFLATE_DOWN} so
    *  ground-level corner picks still cover the substrate directly below
    *  the crop tile. */
   public BlockPos scanMin() {
      BlockPos m = minCorner();
      return new BlockPos(m.getX(), m.getY() - Y_INFLATE_DOWN, m.getZ());
   }

   /** Max corner of the effective scan/containment volume — Y raised by
    *  {@link #Y_INFLATE_UP} to cover crops growing on top of farmland the
    *  player picked at the surface. */
   public BlockPos scanMax() {
      BlockPos m = maxCorner();
      return new BlockPos(m.getX(), m.getY() + Y_INFLATE_UP, m.getZ());
   }

   public boolean contains(BlockPos p) {
      BlockPos mn = scanMin(), mx = scanMax();
      return p.getX() >= mn.getX() && p.getX() <= mx.getX()
          && p.getY() >= mn.getY() && p.getY() <= mx.getY()
          && p.getZ() >= mn.getZ() && p.getZ() <= mx.getZ();
   }

   /** Axis-aligned AABB intersection. Inclusive on both ends. */
   public boolean overlaps(FieldRegion other) {
      BlockPos a1 = minCorner(), a2 = maxCorner();
      BlockPos b1 = other.minCorner(), b2 = other.maxCorner();
      return a1.getX() <= b2.getX() && a2.getX() >= b1.getX()
          && a1.getY() <= b2.getY() && a2.getY() >= b1.getY()
          && a1.getZ() <= b2.getZ() && a2.getZ() >= b1.getZ();
   }

   public int sizeX() { return maxCorner().getX() - minCorner().getX() + 1; }
   public int sizeZ() { return maxCorner().getZ() - minCorner().getZ() + 1; }

   public int volume() {
      int sy = maxCorner().getY() - minCorner().getY() + 1;
      return sizeX() * sy * sizeZ();
   }

   public BlockPos centre() {
      BlockPos mn = minCorner(), mx = maxCorner();
      return new BlockPos((mn.getX() + mx.getX()) / 2, (mn.getY() + mx.getY()) / 2,
         (mn.getZ() + mx.getZ()) / 2);
   }

   /** Short prose like "8×4 west of (130,64,-22)" — for chat / log messages. */
   public String shortLabel(BlockPos reference) {
      BlockPos c = centre();
      int dx = c.getX() - reference.getX();
      int dz = c.getZ() - reference.getZ();
      String dir;
      if (Math.abs(dx) > Math.abs(dz)) dir = dx > 0 ? "east" : "west";
      else                             dir = dz > 0 ? "south" : "north";
      return sizeX() + "×" + sizeZ() + " " + dir + " of " + reference.toShortString();
   }
}

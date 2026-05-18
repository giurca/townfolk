package com.yucareux.townfolk.town;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Immutable XZ-disk-union view of a town's effective area.
 *
 * <p>A town's "coverage" is the union of horizontal disks around its
 * source blocks — initially just the master Town Square block, and
 * later also any registered auxiliary blocks (Trade Post, future
 * watchtowers, warehouses, etc.). Every "is this position part of this
 * town?" check in the codebase routes through {@link #contains} so we
 * have one consistent answer.
 *
 * <p>Decisions baked in:
 * <ul>
 *   <li><b>XZ-only.</b> Coverage ignores Y entirely. Towns are flat
 *       circles on the map, not spheres — a villager 80 blocks below
 *       the square is still "in town." Matches how players think about
 *       towns and how the old {@code defaultRadius} check worked.
 *   <li><b>Disk union, not bounding box.</b> Two source blocks 100
 *       blocks apart, each with radius 64, produce a peanut shape, not
 *       a rectangle. Anything inside EITHER disk is covered.
 *   <li><b>Immutable.</b> A coverage instance is a snapshot; rebuild
 *       it after the town's source set changes. Cheap (single arraylist
 *       copy), and ducks every concurrency question.
 *   <li><b>No level / dimension awareness.</b> Coverage is pure
 *       geometry. Callers that care about dimensions must scope their
 *       own lookup to the correct {@code ServerLevel} before asking.
 * </ul>
 *
 * <p>Stage 1.1 (this commit) only supports single-disk coverage built
 * from the master Town Square. Stage 1.3 extends construction to merge
 * in registered auxiliary blocks via {@code TownAuxiliary}.
 *
 * @see TownData
 */
public final class TownCoverage {

   /** One contributing source's XZ disk. */
   public record Disk(BlockPos center, int radius) {
      public Disk {
         if (center == null) throw new IllegalArgumentException("center must not be null");
         if (radius < 0)     throw new IllegalArgumentException("radius must be >= 0");
      }

      /** Squared XZ distance from this disk's centre to {@code pos}. */
      private long distSqXZ(BlockPos pos) {
         long dx = (long) pos.getX() - center.getX();
         long dz = (long) pos.getZ() - center.getZ();
         return dx * dx + dz * dz;
      }

      /** True iff {@code pos} lies within this disk (XZ only). */
      public boolean contains(BlockPos pos) {
         long r = radius;
         return distSqXZ(pos) <= r * r;
      }
   }

   private final List<Disk> disks;

   private TownCoverage(List<Disk> disks) {
      // Defensive copy + unmodifiable wrap. Callers can't mutate our
      // state, and our methods can iterate without snapshotting.
      this.disks = Collections.unmodifiableList(new ArrayList<>(disks));
   }

   /** Build coverage from a single master source. The typical Stage-1.1
    *  case: just the Town Square block contributes. */
   public static TownCoverage ofMaster(BlockPos masterPos, int masterRadius) {
      return new TownCoverage(List.of(new Disk(masterPos, masterRadius)));
   }

   /** Build coverage from an explicit list of disks. Used by
    *  {@code TownSquareBlockEntity.coverage()} which assembles master +
    *  auxiliary disks each call. */
   public static TownCoverage of(List<Disk> disks) {
      return new TownCoverage(disks);
   }

   /** Empty coverage — never contains anything. Useful default for
    *  towns mid-init or for code paths that need a non-null coverage
    *  even when no master is loaded. */
   public static TownCoverage empty() {
      return new TownCoverage(List.of());
   }

   /** All contributing disks. Read-only view. */
   public List<Disk> disks() { return disks; }

   /** True iff {@code pos} lies within at least one contributing disk
    *  (XZ only). */
   public boolean contains(BlockPos pos) {
      for (Disk d : disks) if (d.contains(pos)) return true;
      return false;
   }

   /** Squared XZ distance to the centre of the NEAREST disk, in blocks².
    *  Useful when a caller wants "how far from town" rather than just
    *  "in or out." Returns {@link Long#MAX_VALUE} if coverage is empty. */
   public long nearestDistSqXZ(BlockPos pos) {
      long best = Long.MAX_VALUE;
      for (Disk d : disks) {
         long dsq = d.distSqXZ(pos);
         if (dsq < best) best = dsq;
      }
      return best;
   }

   /** Largest radius any single contributing disk has. Useful for
    *  callers that still need a scalar "radius" — e.g. legacy
    *  display strings or admin commands that report a number. Returns
    *  0 for empty coverage. */
   public int maxRadius() {
      int max = 0;
      for (Disk d : disks) if (d.radius() > max) max = d.radius();
      return max;
   }

   /** True iff this coverage contributes no area at all. */
   public boolean isEmpty() { return disks.isEmpty(); }
}

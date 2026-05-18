package com.yucareux.townfolk.town;

import net.minecraft.core.BlockPos;

/**
 * One registered auxiliary block within a town. Immutable; the registry
 * on {@link TownData} stores a list of these.
 *
 * @param pos              the block's position (master key for lookup
 *                         and deregistration)
 * @param type             which kind of auxiliary it is — determines the
 *                         coverage disk it contributes
 * @param registeredAtTick gametime when the block was registered; useful
 *                         for "Trade Post placed 3 days ago" displays and
 *                         for any future seniority-based logic
 */
public record TownAuxiliaryEntry(BlockPos pos, TownAuxiliaryType type, long registeredAtTick) {
   public TownAuxiliaryEntry {
      if (pos == null)  throw new IllegalArgumentException("pos must not be null");
      if (type == null) throw new IllegalArgumentException("type must not be null");
   }

   /** Convenience: this entry's coverage-disk contribution. */
   public TownCoverage.Disk disk() {
      return new TownCoverage.Disk(pos, type.contributedRadius());
   }
}

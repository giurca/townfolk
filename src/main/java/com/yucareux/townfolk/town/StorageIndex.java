package com.yucareux.townfolk.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

/**
 * Single source of truth for "is this block entity a player-stockpile
 * container, and how do I get to it?" Replaces the previously-scattered
 * inline checks in {@code ToolDispatcher.findNearbyContainer},
 * {@code ParcelRoutine.nearestStorageWithin},
 * {@code ParcelRoutine.discoverNearbyContainers},
 * {@code TownTreasury.findNearestWith}, and
 * {@code TownTreasury.findNearestForDeposit}.
 *
 * Whitelist (player stockpile, accepts arbitrary items):
 *   - {@link BarrelBlockEntity}
 *   - {@link ChestBlockEntity} (incl. trapped chests)
 *   - {@link ShulkerBoxBlockEntity}
 *
 * Explicitly NOT included: furnaces, hoppers, brewing stands, dispensers,
 * droppers, beehives, jukeboxes — they all implement {@link Container}
 * but routing a deposit at one of them silently sends harvest into the
 * wrong slot.
 */
public final class StorageIndex {

   /** True if the block entity is a player-stockpile container. */
   public static boolean isStorage(BlockEntity be) {
      return be instanceof BarrelBlockEntity
          || be instanceof ChestBlockEntity
          || be instanceof ShulkerBoxBlockEntity;
   }

   /** Result of a proximity scan — both the position and the Container view
    *  in one tuple, so callers don't have to scan twice. */
   public record Hit(BlockPos pos, Container container) {}

   /** Find the nearest storage container within a cube of {@code radius}
    *  blocks (XZ) and ±2 Y. Returns {@code null} if none found. */
   public static Hit nearest(ServerLevel level, BlockPos centre, int radius) {
      return nearestMatching(level, centre, radius, h -> true);
   }

   /** Find the nearest storage container within range that ALSO passes a
    *  caller-supplied predicate. Lets the storage code differentiate
    *  between "any nearby storage" and "any nearby storage that is
    *  REGISTERED + accepts THIS item" — critical for deposit/withdraw
    *  routing when several barrels are clustered: without the
    *  predicate, the geometrically-closest barrel always wins, even
    *  if it's empty / has the wrong filter, leading to a ping-pong
    *  loop where treasury routing keeps walking the villager back to
    *  the right barrel only for {@code nearest()} to redirect them to
    *  the wrong-but-closer one again. */
   public static Hit nearestMatching(ServerLevel level, BlockPos centre, int radius,
                                      java.util.function.Predicate<Hit> ok) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      Hit best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -2; dy <= 2; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               var be = level.getBlockEntity(cur);
               if (!isStorage(be)) continue;
               if (!(be instanceof Container c)) continue;
               Hit candidate = new Hit(cur.immutable(), c);
               if (!ok.test(candidate)) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) {
                  bestDist = d;
                  best = candidate;
               }
            }
         }
      }
      return best;
   }

   private StorageIndex() {}
}

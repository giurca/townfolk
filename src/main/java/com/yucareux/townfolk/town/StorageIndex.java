package com.yucareux.townfolk.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Single source of truth for "is this block at a player-stockpile
 * container, and how do I get to it?" Replaces the previously-scattered
 * inline checks across {@code ToolDispatcher}, {@code ParcelRoutine},
 * {@code TownTreasury}, and {@code InteractHandler}.
 *
 * <h2>What counts as storage</h2>
 * Any block whose position exposes NeoForge's
 * {@code Capabilities.ItemHandler.BLOCK} capability. That covers:
 * <ul>
 *   <li>Vanilla chests / barrels / shulker boxes (native exposure)
 *   <li>Sophisticated Storage barrels and drawers
 *   <li>Create item vaults
 *   <li>Functional Storage drawers
 *   <li>Iron / Diamond / etc Chests
 *   <li>Any future storage mod that follows the standard capability
 *       convention
 * </ul>
 *
 * <p>Hoppers / dispensers / droppers / furnaces / brewing stands /
 * beehives also expose the capability but tend to be functional
 * machinery rather than player stockpile. We do NOT specifically
 * exclude them — the player's deliberate sneak-right-click registration
 * is the gate. If a player explicitly registers a hopper as storage,
 * they get what they asked for.
 */
public final class StorageIndex {

   /** True if a block at the given position exposes an item-handler
    *  capability — i.e. counts as storage. {@code be} is kept as a
    *  hint for hot-path callers who already have the block entity
    *  in hand, but the actual decision is capability-driven. */
   public static boolean isStorage(BlockEntity be) {
      if (be == null) return false;
      if (!(be.getLevel() instanceof ServerLevel level)) return false;
      return ContainerAdapters.isStorageAt(level, be.getBlockPos());
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
               // Capability-driven probe instead of an instanceof BE
               // check — covers Sophisticated Storage / Create / any
               // mod that exposes the standard item-handler capability.
               Container c = ContainerAdapters.at(level, cur);
               if (c == null) continue;
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

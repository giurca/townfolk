package com.yucareux.townfolk.world.verbs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared helpers used by multiple verb handlers — the small utility
 * functions that used to be private statics on {@code ToolDispatcher}.
 *
 * <p>Stage 16b.2: as do* methods move into individual {@code *Verb}
 * files, anything they shared (block scanning, fuzzy matching, item
 * resolution, etc.) lands here so each verb file can stay narrow.
 */
public final class VerbUtils {

   private VerbUtils() {}

   /** Scan a cube of {@code [-radius, radius]} around {@code centre},
    *  return the closest block matching {@code pred}, or null. Used
    *  for bed / workstation / barrel proximity searches. */
   public static BlockPos findBlock(ServerLevel level, BlockPos centre, int radius,
                                     Predicate<BlockState> pred) {
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
               cur.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
               if (!pred.test(level.getBlockState(cur))) continue;
               double d = cur.distSqr(centre);
               if (d < bestDist) { bestDist = d; best = cur.immutable(); }
            }
         }
      }
      return best;
   }

   /** Cheap word-overlap score, case-insensitive. Splits on non-word
    *  characters, drops trivial English stop-words, intersects the
    *  two sets. Good enough for fuzzy todo matching — overkill would
    *  cost more than it saves at the volumes we deal with. */
   public static int fuzzyOverlap(String a, String b) {
      Set<String> aw = new HashSet<>(Arrays.asList(a.split("\\W+")));
      Set<String> bw = new HashSet<>(Arrays.asList(b.split("\\W+")));
      aw.remove(""); bw.remove("");
      Set<String> stop = new HashSet<>(Arrays.asList(
         "the","a","an","to","of","i","me","my","on","at","and","is","be",
         "ill","will","for","that","this"));
      aw.removeAll(stop); bw.removeAll(stop);
      aw.retainAll(bw);
      return aw.size();
   }
}

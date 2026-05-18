package com.yucareux.townfolk.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Vanilla's pathfinder treats a CLOSED fence gate as
 * {@link PathType#FENCE} — i.e. impassable. That makes any pen with a
 * shut gate functionally unreachable: pathing fails entirely (path null,
 * villager stuck) instead of routing through the gate and letting the
 * AI open it on approach.
 *
 * Wooden DOORS, by contrast, are {@link PathType#DOOR_WOOD_CLOSED} —
 * passable if {@code canPassDoors / canOpenDoors} is set, then the
 * mob's AI is expected to open them. Fence gates are mechanically
 * identical (right-click to toggle), they just got a different PathType
 * for historical reasons.
 *
 * This evaluator re-classifies closed wooden fence gates as
 * {@code DOOR_WOOD_CLOSED}. Combined with the villager-side
 * {@code canOpenDoors=true} flag (vanilla Villager constructor sets
 * this) and {@link DoorInteractionGoal}'s path-lookahead opener, our
 * townsfolk can now naturally walk into and out of fenced pens —
 * including the close-behind that keeps sheep contained.
 *
 * Iron fence gates? There's no such thing in vanilla; if a mod adds one
 * we'd want to leave it as FENCE so non-redstone access is denied.
 * Iron DOORS still resolve to {@code DOOR_IRON_CLOSED} via super,
 * which the villager treats as impassable — same as vanilla.
 */
public final class TownsfolkNodeEvaluator extends WalkNodeEvaluator {

   @Override
   public PathType getPathType(PathfindingContext context, int x, int y, int z) {
      PathType base = super.getPathType(context, x, y, z);
      if (base == PathType.FENCE) {
         BlockState state = context.getBlockState(new BlockPos(x, y, z));
         if (state.getBlock() instanceof FenceGateBlock) {
            // A closed wooden fence gate — treat exactly like a closed
            // wooden door so the pathfinder routes through it.
            return PathType.DOOR_WOOD_CLOSED;
         }
      }
      return base;
   }
}

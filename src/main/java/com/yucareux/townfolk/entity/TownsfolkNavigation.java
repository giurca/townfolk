package com.yucareux.townfolk.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Drop-in replacement for vanilla {@link GroundPathNavigation} that uses
 * {@link TownsfolkNodeEvaluator} — same as ground nav in every respect
 * except that closed wooden fence gates resolve to a passable
 * {@code DOOR_WOOD_CLOSED} path type instead of impassable {@code FENCE}.
 *
 * Pairs with {@link DoorInteractionGoal} which opens the gate as the
 * villager approaches the path node landing on it, then closes it
 * behind once they're well past.
 */
public final class TownsfolkNavigation extends GroundPathNavigation {

   public TownsfolkNavigation(Mob mob, Level level) {
      super(mob, level);
   }

   @Override
   protected PathFinder createPathFinder(int maxVisitedNodes) {
      this.nodeEvaluator = new TownsfolkNodeEvaluator();
      this.nodeEvaluator.setCanPassDoors(true);
      this.nodeEvaluator.setCanOpenDoors(true);
      return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
   }
}

package com.yucareux.townfolk.entity;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Vacuum up nearby {@link ItemEntity} drops within a small radius. The
 * vanilla {@link Villager} only auto-picks the narrow set of items defined
 * by its brain (farm-block items + bread); we neutered the brain, and even
 * if we hadn't, we want wool (from shearing) and any other harvest leftovers
 * to flow back into the villager's inventory so the auto-deposit step can
 * shuttle them to a barrel.
 *
 * Concretely this fixes: villager shears a sheep with a full or near-full
 * inventory → wool drops as an item entity → was previously left on the
 * grass forever. Now the villager will mop it up on its next pass within
 * the radius.
 *
 * Operates passively — no movement is initiated. We only suck in items the
 * villager happens to walk past. That's intentional: chasing every dropped
 * item across the map is a different design (and would compete with the
 * BlockTaskQueue paths).
 */
public class ItemPickupGoal extends Goal {

   private final Villager mob;

   /** Half-extent of the AABB we scan each tick. 1.5 is roughly arm's reach
    *  plus a tile — wide enough to catch drops at the villager's feet and
    *  one tile ahead, narrow enough to not silently teleport items from
    *  off-screen. */
   private static final double PICKUP_RADIUS = 1.5;

   public ItemPickupGoal(Villager mob) {
      this.mob = mob;
      setFlags(EnumSet.noneOf(Flag.class));   // runs alongside everything else
   }

   @Override
   public boolean canUse() { return true; }

   @Override
   public boolean canContinueToUse() { return true; }

   @Override
   public boolean requiresUpdateEveryTick() { return false; }

   @Override
   public void tick() {
      // Cheap AABB scan, executes every ~10 ticks (Goal default cadence).
      AABB box = mob.getBoundingBox().inflate(PICKUP_RADIUS);
      List<ItemEntity> items = mob.level().getEntitiesOfClass(ItemEntity.class, box,
         e -> e.isAlive() && !e.hasPickUpDelay() && !e.getItem().isEmpty());
      if (items.isEmpty()) return;

      for (ItemEntity ie : items) {
         ItemStack stack = ie.getItem().copy();
         int before = stack.getCount();
         ItemStack leftover = mob.getInventory().addItem(stack);
         int picked;
         if (leftover == null || leftover.isEmpty()) {
            picked = before;
            ie.discard();
         } else if (leftover.getCount() < before) {
            picked = before - leftover.getCount();
            ie.setItem(leftover);
         } else {
            picked = 0;     // bag full, nothing absorbed
         }
         if (picked > 0) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ie.getItem().getItem());
            com.yucareux.townfolk.diag.VerboseLog.write("INV_PICKUP",
               "actor=" + nameOf() + " item=" + (id == null ? "?" : id.getPath())
               + " count=" + picked + " leftover=" + (leftover == null ? 0 : leftover.getCount()),
               "");
         }
      }
   }

   private String nameOf() {
      return mob.hasCustomName() ? mob.getCustomName().getString()
                                 : mob.getUUID().toString().substring(0, 8);
   }
}

package com.yucareux.townfolk.world.livestock;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.MemoryStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MilkBucketItem;

/**
 * Milk an adult cow with a bucket. The villager swaps one empty bucket
 * out of inventory for a milk bucket, identical to vanilla
 * {@link MilkBucketItem}-style interaction. Cows don't expose a cooldown
 * — the milkable predicate is just "adult". To prevent infinite milk
 * loops on a single cow the parcel routine relies on the per-tick
 * deposit cycle: once the bag has 1+ milk_bucket(s), the deposit rule
 * (keep 0, trigger 1) fires and the villager banks before milking again.
 */
public final class MilkCowTask implements LivestockTask {

   @Override public String id()   { return "milk_cow"; }
   @Override public String verb() { return "milk"; }
   @Override public Class<? extends Animal> targetType() { return Cow.class; }
   @Override public String toolItemId() { return "minecraft:bucket"; }

   @Override public boolean hasTool(Villager v) {
      return LivestockTask.hasItem(v, "minecraft:bucket");
   }

   @Override public boolean ready(ServerLevel level, Animal animal, Villager v) {
      // Skip the species' baby form (mooshrooms extend Cow, that's fine —
      // they'd also milk to a bucket). Skip when the villager already
      // carries enough milk to trigger a deposit — the bag-pressure logic
      // would otherwise sweep them right back here next tick.
      if (!(animal instanceof Cow)) return false;
      if (animal.isBaby()) return false;
      // One milk per autonomous tick — let the deposit routine empty the
      // bag before the next milking.
      if (LivestockTask.hasItem(v, "minecraft:milk_bucket")) return false;
      // Stockpile cap: stop milking once the villager's town has
      // enough milk (see ProductionTargets). Hysteresis auto-resumes
      // when stock drops below min.
      var comp = v.getData(com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      if (comp.townSquarePos() == 0L) return true;        // no town → no cap
      var townPos = net.minecraft.core.BlockPos.of(comp.townSquarePos());
      int total = com.yucareux.townfolk.town.TreasuryCache.totalOf(level, "minecraft:milk_bucket");
      return com.yucareux.townfolk.town.ProductionTargets.shouldProduce(
         level, townPos, "minecraft:milk_bucket", total);
   }

   @Override public String perform(ServerLevel level, Villager v, Animal animal,
                                   FieldRegion parcel, TownSquareBlockEntity town) {
      if (!(animal instanceof Cow cow)) {
         throw new RuntimeException("target is no longer a cow");
      }
      // Remove one bucket from inventory, add one milk bucket back.
      var inv = v.getInventory();
      int bucketSlot = -1;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (inv.getItem(i).getItem() == Items.BUCKET) {
            bucketSlot = i; break;
         }
      }
      if (bucketSlot < 0) {
         throw new RuntimeException("no bucket in bag at milk time");
      }
      inv.getItem(bucketSlot).shrink(1);
      if (inv.getItem(bucketSlot).isEmpty()) {
         inv.setItem(bucketSlot, ItemStack.EMPTY);
      }
      ItemStack milk = new ItemStack(Items.MILK_BUCKET, 1);
      ItemStack leftover = inv.addItem(milk);
      if (leftover != null && !leftover.isEmpty()) {
         var ie = new ItemEntity(level, v.getX(), v.getY(), v.getZ(), leftover);
         ie.setPickUpDelay(20);
         level.addFreshEntity(ie);
      }
      level.playSound(null, cow.blockPosition(),
         SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);

      long day = level.getGameTime() / 24000L;
      String parcelTag = parcel == null ? "this spot"
         : parcel.shortLabel(town.getBlockPos());
      MemoryStore.write(v, "work", day,
         "At my " + parcelTag + " I milked a cow — got 1 bucket of milk.");
      return "milked a cow (1× milk bucket)";
   }
}

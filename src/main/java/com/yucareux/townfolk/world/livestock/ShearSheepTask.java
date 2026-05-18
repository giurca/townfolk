package com.yucareux.townfolk.world.livestock;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.MemoryStore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Shear an unsheared adult sheep with shears. Yields 1–3 wool of the
 * sheep's color into the villager's bag (with overflow dropping at
 * their feet). Extracted from the original inline routine in
 * {@code ParcelRoutine.enqueueShear} so the registry can iterate
 * uniformly across all livestock tasks.
 */
public final class ShearSheepTask implements LivestockTask {

   @Override public String id()    { return "shear_sheep"; }
   @Override public String verb()  { return "shear"; }
   @Override public Class<? extends Animal> targetType() { return Sheep.class; }
   @Override public String toolItemId() { return "minecraft:shears"; }
   @Override public TagKey<Item> toolTag() { return null; }

   @Override public boolean hasTool(Villager v) {
      return LivestockTask.hasItem(v, "minecraft:shears");
   }

   @Override public boolean ready(ServerLevel level, Animal animal, Villager v) {
      if (!(animal instanceof Sheep sh) || sh.isSheared() || sh.isBaby()) return false;
      // Stockpile cap per wool colour, scoped to the villager's town
      // — different towns can have different wool policies.
      var comp = v.getData(com.yucareux.townfolk.registry.ModRegistries.LLM_VILLAGER.get());
      if (comp.townSquarePos() == 0L) return true;        // no town → no cap
      var townPos = net.minecraft.core.BlockPos.of(comp.townSquarePos());
      String woolId = "minecraft:" + sh.getColor().getName() + "_wool";
      int total = com.yucareux.townfolk.town.TreasuryCache.totalOf(level, woolId);
      return com.yucareux.townfolk.town.ProductionTargets.shouldProduce(level, townPos, woolId, total);
   }

   @Override public String perform(ServerLevel level, Villager v, Animal animal,
                                   FieldRegion parcel, TownSquareBlockEntity town) {
      if (!(animal instanceof Sheep sh)) {
         throw new RuntimeException("target is no longer a sheep");
      }
      if (sh.isSheared()) {
         return "sheep was already sheared";
      }
      var color = sh.getColor();
      sh.setSheared(true);
      int qty = 1 + level.getRandom().nextInt(3);
      Item wool = BuiltInRegistries.ITEM.get(ResourceLocation.parse(
         "minecraft:" + color.getName() + "_wool"));
      ItemStack stack = new ItemStack(wool, qty);
      ItemStack leftover = v.getInventory().addItem(stack);
      if (leftover != null && !leftover.isEmpty()) {
         var ie = new ItemEntity(level, v.getX(), v.getY(), v.getZ(), leftover);
         ie.setPickUpDelay(20);
         level.addFreshEntity(ie);
      }
      level.playSound(null, sh.blockPosition(),
         SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0F, 1.0F);

      long day = level.getGameTime() / 24000L;
      String parcelTag = parcel == null ? "this spot"
         : parcel.shortLabel(town.getBlockPos());
      MemoryStore.write(v, "work", day,
         "At my " + parcelTag + " I sheared a " + color.getName() + " sheep — got "
            + qty + "× wool.");
      return "sheared a " + color.getName() + " sheep (" + qty + "× wool)";
   }

}

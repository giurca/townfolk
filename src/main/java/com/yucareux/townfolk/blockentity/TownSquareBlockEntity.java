package com.yucareux.townfolk.blockentity;

import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownCoverage;
import com.yucareux.townfolk.town.TownData;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds a {@link TownData} keyed to this block's position. NBT round-trips
 * the town's editable config + villager roster.
 *
 * Also maintains a per-Level weak registry of loaded instances so background
 * services (nightly compaction, future cron jobs) can iterate towns without
 * scanning every loaded chunk.
 */
public class TownSquareBlockEntity extends BlockEntity {

   private static final java.util.Map<Level, Set<TownSquareBlockEntity>> LOADED =
      Collections.synchronizedMap(new WeakHashMap<>());

   public static Set<TownSquareBlockEntity> loadedIn(Level level) {
      Set<TownSquareBlockEntity> set = LOADED.get(level);
      return set == null ? Set.of() : Set.copyOf(set);
   }

   private final TownData town;

   public TownSquareBlockEntity(BlockPos pos, BlockState state) {
      super(ModRegistries.TOWN_SQUARE_BE.get(), pos, state);
      this.town = new TownData(pos);
   }

   public TownData getTown() {
      return this.town;
   }

   /**
    * Build a fresh {@link TownCoverage} snapshot: a disk around the
    * master block plus one disk per registered auxiliary block.
    *
    * <p>Cheap to call — coverage is a small immutable list. Callers
    * should not cache it across town-data changes (additions / removals
    * of source blocks); just rebuild when needed.
    */
   public TownCoverage coverage() {
      java.util.List<TownCoverage.Disk> disks = new java.util.ArrayList<>();
      disks.add(new TownCoverage.Disk(this.getBlockPos(), this.town.defaultRadius()));
      for (var aux : this.town.auxiliaries()) {
         disks.add(aux.disk());
      }
      return TownCoverage.of(disks);
   }

   @Override
   public void onLoad() {
      super.onLoad();
      if (this.level != null && !this.level.isClientSide) {
         LOADED.computeIfAbsent(this.level, k -> Collections.synchronizedSet(new java.util.HashSet<>())).add(this);
      }
   }

   @Override
   public void setRemoved() {
      if (this.level != null && !this.level.isClientSide) {
         Set<TownSquareBlockEntity> set = LOADED.get(this.level);
         if (set != null) set.remove(this);
      }
      super.setRemoved();
   }

   @Override
   protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.saveAdditional(tag, registries);
      tag.put("town", this.town.save());
   }

   @Override
   protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      if (tag.contains("town")) {
         this.town.load(tag.getCompound("town"));
      }
   }
}

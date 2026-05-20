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

   /** Production-target rows captured at deserialization time, applied
    *  to the per-level {@code ProductionTargets} SavedData once the BE
    *  has been attached to a real {@link Level} in {@link #onLoad}.
    *  Null on a fresh place / when nothing was preserved. */
   private CompoundTag pendingProductionTargets;

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
      if (this.level instanceof net.minecraft.server.level.ServerLevel sl) {
         LOADED.computeIfAbsent(this.level, k -> Collections.synchronizedSet(new java.util.HashSet<>())).add(this);

         // ── Apply any production targets captured during deserialization. ──
         // These were preserved through Charter Stone break + replace via
         // the BlockItem BLOCK_ENTITY_DATA round-trip; they live in a
         // separate per-level SavedData (ProductionTargets) so they don't
         // come along with TownData automatically. Re-key them to the
         // BE's CURRENT worldPosition — relocating the stone shifts the
         // owning town pos, and the targets follow.
         if (this.pendingProductionTargets != null) {
            CompoundTag list = this.pendingProductionTargets;
            this.pendingProductionTargets = null;
            for (String itemId : list.getAllKeys()) {
               CompoundTag entry = list.getCompound(itemId);
               int min = entry.getInt("min");
               int max = entry.getInt("max");
               com.yucareux.townfolk.town.ProductionTargets.setBounds(
                  sl, this.worldPosition, itemId, min, max);
            }
            com.yucareux.townfolk.diag.VerboseLog.write("PRODUCTION_TARGETS_RESTORED",
               "town=" + this.worldPosition.toShortString()
                  + " count=" + list.getAllKeys().size(), "");
         }
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

      // Bundle the town's ProductionTargets snapshot inline so the
      // break-and-replace round-trip (via BlockItem.setBlockEntityData)
      // carries them through. They live in a separate SavedData keyed
      // by townSquarePos, so they wouldn't follow the TownData blob
      // otherwise.
      if (this.level instanceof net.minecraft.server.level.ServerLevel sl) {
         var snapshot = com.yucareux.townfolk.town.ProductionTargets.snapshot(
            sl, this.worldPosition);
         if (!snapshot.isEmpty()) {
            CompoundTag list = new CompoundTag();
            for (var e : snapshot.entrySet()) {
               CompoundTag entry = new CompoundTag();
               entry.putInt("min", e.getValue().min());
               entry.putInt("max", e.getValue().max());
               list.put(e.getKey(), entry);
            }
            tag.put("production_targets", list);
         }
      }
   }

   @Override
   protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      if (tag.contains("town")) {
         this.town.load(tag.getCompound("town"));
      }
      // Capture the production-targets blob for application in onLoad
      // once the BE is actually wired to a ServerLevel. We can't write
      // to ProductionTargets here — level may be null mid-load.
      if (tag.contains("production_targets")) {
         this.pendingProductionTargets = tag.getCompound("production_targets");
      }
   }

}

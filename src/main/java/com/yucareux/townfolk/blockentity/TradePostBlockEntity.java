package com.yucareux.townfolk.blockentity;

import com.yucareux.townfolk.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Trade Post. Stores the position of the Town
 * Square that owns this post.
 *
 * <p>"Owning town" is determined at place-time (see
 * {@link com.yucareux.townfolk.world.TradePostHooks}) by finding which
 * town's coverage contains the place position. Persisted so we don't
 * have to re-scan on every load.
 *
 * <p>Owner zero = unowned. Should not happen in practice (placement
 * is rejected outside any town's coverage) but the field defaults
 * sensibly so an old / malformed save doesn't crash.
 */
public class TradePostBlockEntity extends BlockEntity {

   /** Packed BlockPos of the owning Town Square. 0 = unowned. */
   private long ownerTownPos;

   public TradePostBlockEntity(BlockPos pos, BlockState state) {
      super(ModRegistries.TRADE_POST_BE.get(), pos, state);
      this.ownerTownPos = 0L;
   }

   public long ownerTownPos() { return this.ownerTownPos; }

   public void setOwnerTownPos(long pos) {
      if (this.ownerTownPos != pos) {
         this.ownerTownPos = pos;
         this.setChanged();
      }
   }

   @Override
   protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.saveAdditional(tag, registries);
      tag.putLong("ownerTown", this.ownerTownPos);
   }

   @Override
   protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      this.ownerTownPos = tag.contains("ownerTown") ? tag.getLong("ownerTown") : 0L;
   }
}

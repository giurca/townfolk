package com.yucareux.townfolk.blockentity;

import com.yucareux.townfolk.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Backing block entity for a {@code townfolk:tavern_hearth} block —
 * the marker for the
 * {@link com.yucareux.townfolk.building.BuildingTemplates#TAVERN}
 * template.
 *
 * <p>Carries one piece of state: an optional player-set label ("The
 * Drowned Anchor", etc.). The recognition + admin layers use the
 * label for log entries and prompt context. Empty label = "the
 * tavern" in narration.
 *
 * <p>The block itself doesn't gate town features or coverage — once
 * a tavern is recognized by
 * {@link com.yucareux.townfolk.building.BuildingRegistry}, the
 * leisure layer (Stage 11b) picks up the active recognition and
 * unlocks the tavern choice for nearby villagers.
 */
public class TavernHearthBlockEntity extends BlockEntity {

   private String label = "";

   public TavernHearthBlockEntity(BlockPos pos, BlockState state) {
      super(ModRegistries.TAVERN_HEARTH_BE.get(), pos, state);
   }

   public String label() { return this.label; }

   public void setLabel(String label) {
      this.label = label == null ? "" : label;
      setChanged();
   }

   @Override
   protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.saveAdditional(tag, registries);
      if (!this.label.isEmpty()) tag.putString("label", this.label);
   }

   @Override
   protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      this.label = tag.contains("label") ? tag.getString("label") : "";
   }
}

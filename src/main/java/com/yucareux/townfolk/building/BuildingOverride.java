package com.yucareux.townfolk.building;

import net.minecraft.nbt.CompoundTag;

/**
 * Per-building overrides for {@link BuildingTemplate} limits.
 *
 * <p>Used by Stage 10b's Building Permit item: a player can spend
 * one permit to extend a single building's {@code maxVolume} /
 * {@code maxHeight} caps beyond the template defaults. Default
 * value of {@code -1} for either field means "use template default."
 *
 * <p>Records are immutable; mutating the override means writing a
 * new one into the {@link RecognizedBuilding}'s registry slot.
 *
 * @param maxVolume override for the air-block volume cap, or -1
 * @param maxHeight override for the interior height cap, or -1
 */
public record BuildingOverride(int maxVolume, int maxHeight) {

   public static final BuildingOverride NONE = new BuildingOverride(-1, -1);

   /** Effective volume cap: override if set, else template default. */
   public int effectiveMaxVolume(BuildingTemplate t) {
      return maxVolume > 0 ? maxVolume : t.maxVolume();
   }

   /** Effective height cap: override if set, else template default. */
   public int effectiveMaxHeight(BuildingTemplate t) {
      return maxHeight > 0 ? maxHeight : t.maxHeight();
   }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      if (maxVolume > 0) tag.putInt("maxVolume", maxVolume);
      if (maxHeight > 0) tag.putInt("maxHeight", maxHeight);
      return tag;
   }

   public static BuildingOverride load(CompoundTag tag) {
      int vol = tag.contains("maxVolume") ? tag.getInt("maxVolume") : -1;
      int hgt = tag.contains("maxHeight") ? tag.getInt("maxHeight") : -1;
      return (vol < 0 && hgt < 0) ? NONE : new BuildingOverride(vol, hgt);
   }
}

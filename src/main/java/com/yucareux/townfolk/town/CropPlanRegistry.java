package com.yucareux.townfolk.town;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level persistent map of parcel id → {@link CropPlan}.
 *
 * Kept SEPARATE from {@link com.yucareux.townfolk.villager.FieldRegion} on
 * purpose: plans need to be editable without touching the FieldRegion
 * record's codec or the villager's parcel list, and they belong at the
 * world level (so e.g. unloading the owner villager doesn't take the plan
 * with them).
 *
 * Lookups fall back to {@link CropPlan#DEFAULT} when no entry exists, so
 * parcels created before this system was added keep working — they just
 * behave as "wheat at weight 1".
 *
 * Cleanup: callers MUST {@link #forget} a parcel id when its parcel is
 * dropped (via the unbind / drop_parcel paths) or the registry would
 * gradually accumulate dead entries.
 */
public final class CropPlanRegistry extends SavedData {

   public static final String STORAGE_KEY = Townfolk.MODID + "_crop_plan_registry";

   private final Map<String, CropPlan> byParcelId = new LinkedHashMap<>();

   private CropPlanRegistry() {}

   public static CropPlanRegistry get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(
         new SavedData.Factory<>(CropPlanRegistry::new, CropPlanRegistry::load, null),
         STORAGE_KEY);
   }

   public static CropPlan find(ServerLevel level, String parcelId) {
      if (parcelId == null) return CropPlan.DEFAULT;
      CropPlan p = get(level).byParcelId.get(parcelId);
      return p == null ? CropPlan.DEFAULT : p;
   }

   public static void put(ServerLevel level, String parcelId, CropPlan plan) {
      if (parcelId == null || plan == null) return;
      CropPlanRegistry reg = get(level);
      reg.byParcelId.put(parcelId, plan);
      reg.setDirty();
      VerboseLog.write("CROP_PLAN_SET",
         "parcel=" + parcelId + " entries=" + plan.activeEntries().size()
            + " totalWeight=" + plan.totalWeight(),
         plan.targetShares().toString());
   }

   public static void forget(ServerLevel level, String parcelId) {
      if (parcelId == null) return;
      CropPlanRegistry reg = get(level);
      if (reg.byParcelId.remove(parcelId) != null) {
         reg.setDirty();
         VerboseLog.write("CROP_PLAN_FORGET", "parcel=" + parcelId, "");
      }
   }

   // ───── persistence ─────

   @Override
   public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
      ListTag list = new ListTag();
      for (var e : byParcelId.entrySet()) {
         CompoundTag ec = new CompoundTag();
         ec.putString("id", e.getKey());
         ec.put("plan", e.getValue().save());
         list.add(ec);
      }
      tag.put("entries", list);
      return tag;
   }

   private static CropPlanRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
      CropPlanRegistry reg = new CropPlanRegistry();
      if (!tag.contains("entries")) return reg;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         CompoundTag ec = list.getCompound(i);
         String id = ec.getString("id");
         CropPlan plan = CropPlan.load(ec.getCompound("plan"));
         reg.byParcelId.put(id, plan);
      }
      return reg;
   }
}

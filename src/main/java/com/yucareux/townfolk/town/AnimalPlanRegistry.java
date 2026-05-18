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
 * Per-level persistent map of parcel id → {@link AnimalPlan}.
 *
 * <p>Mirror of {@link CropPlanRegistry} for ANIMAL parcels. Lookups
 * default to {@link AnimalPlan#EMPTY} when no entry exists — pre-Stage-5
 * parcels keep their (uncontrolled) behaviour. As with crop plans,
 * callers MUST {@link #forget} a parcel id when its parcel is unbound.
 */
public final class AnimalPlanRegistry extends SavedData {

   public static final String STORAGE_KEY = Townfolk.MODID + "_animal_plan_registry";

   private final Map<String, AnimalPlan> byParcelId = new LinkedHashMap<>();

   private AnimalPlanRegistry() {}

   public static AnimalPlanRegistry get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(
         new SavedData.Factory<>(AnimalPlanRegistry::new, AnimalPlanRegistry::load, null),
         STORAGE_KEY);
   }

   public static AnimalPlan find(ServerLevel level, String parcelId) {
      if (parcelId == null) return AnimalPlan.EMPTY;
      AnimalPlan p = get(level).byParcelId.get(parcelId);
      return p == null ? AnimalPlan.EMPTY : p;
   }

   public static void put(ServerLevel level, String parcelId, AnimalPlan plan) {
      if (parcelId == null || plan == null) return;
      AnimalPlanRegistry reg = get(level);
      reg.byParcelId.put(parcelId, plan);
      reg.setDirty();
      VerboseLog.write("ANIMAL_PLAN_SET",
         "parcel=" + parcelId + " entries=" + plan.entries().size(),
         plan.entries().toString());
   }

   public static void forget(ServerLevel level, String parcelId) {
      if (parcelId == null) return;
      AnimalPlanRegistry reg = get(level);
      if (reg.byParcelId.remove(parcelId) != null) {
         reg.setDirty();
         VerboseLog.write("ANIMAL_PLAN_FORGET", "parcel=" + parcelId, "");
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

   private static AnimalPlanRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
      AnimalPlanRegistry reg = new AnimalPlanRegistry();
      if (!tag.contains("entries")) return reg;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         CompoundTag ec = list.getCompound(i);
         String id = ec.getString("id");
         AnimalPlan plan = AnimalPlan.load(ec.getCompound("plan"));
         reg.byParcelId.put(id, plan);
      }
      return reg;
   }
}

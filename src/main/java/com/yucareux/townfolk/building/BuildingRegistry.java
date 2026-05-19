package com.yucareux.townfolk.building;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level persistent map of marker-position → {@link RecognizedBuilding}.
 *
 * <p>Mirrors the {@code StorageRegistry} / {@code CropPlanRegistry}
 * pattern: a {@link SavedData} living on the level, with static
 * helpers for the common ops.
 *
 * <p>Buildings live at the LEVEL granularity, not the town
 * granularity, because:
 * <ul>
 *   <li>recognition is purely geometric — it doesn't need a town to
 *       exist (a marker on its own creates a building, the town
 *       layer just consumes the result)
 *   <li>multiple towns sharing geography can each see all buildings
 *       inside their coverage without duplication
 *   <li>matches how {@code StorageRegistry} already works
 * </ul>
 *
 * <p>Cleanup: when a marker block breaks, the lifecycle hooks call
 * {@link #forget}. When ANY block in a recognized building's boundary
 * or interior changes, the hooks call {@link #invalidate}, which
 * re-runs the recognizer and either keeps the entry (active flag
 * flipped to current result) or removes it after one inactive
 * cycle.
 */
public final class BuildingRegistry extends SavedData {

   public static final String STORAGE_KEY = Townfolk.MODID + "_building_registry";

   /** Keyed by packed BlockPos of the marker. LinkedHashMap so
    *  iteration order is insertion order (newest at the end). */
   private final Map<Long, RecognizedBuilding> byMarker = new LinkedHashMap<>();

   private BuildingRegistry() {}

   public static BuildingRegistry get(ServerLevel level) {
      return level.getDataStorage().computeIfAbsent(
         new SavedData.Factory<>(BuildingRegistry::new, BuildingRegistry::load, null),
         STORAGE_KEY);
   }

   /** Snapshot of every recognized building in this level. */
   public static Collection<RecognizedBuilding> all(ServerLevel level) {
      return List.copyOf(get(level).byMarker.values());
   }

   public static Optional<RecognizedBuilding> findByMarker(ServerLevel level, BlockPos markerPos) {
      return Optional.ofNullable(get(level).byMarker.get(markerPos.asLong()));
   }

   /** Insert or replace the building anchored at {@code marker.markerPos()}. */
   public static void put(ServerLevel level, RecognizedBuilding building) {
      BuildingRegistry reg = get(level);
      reg.byMarker.put(building.markerPos().asLong(), building);
      reg.setDirty();
      VerboseLog.write("BUILDING_PUT",
         "pos=" + building.markerPos().toShortString()
            + " template=" + building.templateId()
            + " active=" + building.active()
            + " floorArea=" + building.floorArea()
            + " height=" + building.height(), "");
   }

   /** Drop the entry at {@code markerPos}. No-op if none exists. */
   public static boolean forget(ServerLevel level, BlockPos markerPos) {
      BuildingRegistry reg = get(level);
      if (reg.byMarker.remove(markerPos.asLong()) != null) {
         reg.setDirty();
         VerboseLog.write("BUILDING_FORGET",
            "pos=" + markerPos.toShortString(), "");
         return true;
      }
      return false;
   }

   /** Re-run the recognizer for the building anchored at this marker.
    *  Updates the entry's active flag + cached interior/boundary
    *  if the structure changed. Removes the entry if the marker
    *  itself is gone from the world. */
   public static void invalidate(ServerLevel level, BlockPos markerPos) {
      BuildingRegistry reg = get(level);
      RecognizedBuilding existing = reg.byMarker.get(markerPos.asLong());
      if (existing == null) return;

      // If the marker block itself is no longer in the world (broken,
      // replaced with something else), drop the entry outright.
      Block markerBlock = level.getBlockState(markerPos).getBlock();
      Optional<BuildingTemplate> tplOpt = BuildingTemplates.byId(existing.templateId());
      if (tplOpt.isEmpty() || !tplOpt.get().isMarker(markerBlock)) {
         reg.byMarker.remove(markerPos.asLong());
         reg.setDirty();
         VerboseLog.write("BUILDING_MARKER_LOST",
            "pos=" + markerPos.toShortString()
               + " template=" + existing.templateId(), "");
         return;
      }

      BuildingTemplate tpl = tplOpt.get();
      BuildingRecognizer.Result result = BuildingRecognizer.recognize(
         level, markerPos, tpl, existing.override());
      long now = level.getGameTime();
      if (result.valid()) {
         RecognizedBuilding updated = new RecognizedBuilding(
            markerPos, existing.templateId(), existing.override(),
            result.boundary(), result.interior(),
            result.floorArea(), result.height(),
            now, true);
         reg.byMarker.put(markerPos.asLong(), updated);
         reg.setDirty();
         if (!existing.active()) {
            VerboseLog.write("BUILDING_RESTORED",
               "pos=" + markerPos.toShortString()
                  + " template=" + existing.templateId(), "");
         }
      } else {
         // Failed validation — mark inactive but keep the entry for
         // one cycle so the player gets a single chat hint instead
         // of a silent disappearance. After they break or rebuild
         // something we'll re-evaluate.
         reg.byMarker.put(markerPos.asLong(),
            existing.withActive(false, now));
         reg.setDirty();
         if (existing.active()) {
            VerboseLog.write("BUILDING_DEACTIVATED",
               "pos=" + markerPos.toShortString()
                  + " template=" + existing.templateId()
                  + " reason=" + result.reason(), "");
         }
      }
   }

   /** Walk every recognized building and return those affected by a
    *  block change at {@code pos}. Used by lifecycle hooks to know
    *  which entries to re-validate. Cheap (O(N buildings × O(1)
    *  hashset lookup)). */
   public static List<RecognizedBuilding> affectedBy(ServerLevel level, BlockPos pos) {
      List<RecognizedBuilding> out = new ArrayList<>();
      for (RecognizedBuilding b : get(level).byMarker.values()) {
         if (b.affectedBy(pos)) out.add(b);
      }
      return out;
   }

   /** True iff any recognized active building of {@code templateId}
    *  exists in this level. Used to gate features (Trade tab gated on
    *  Trade Post block was the earlier pattern; population bonuses on
    *  Town Hall is the same idea). */
   public static int countActiveOfType(ServerLevel level, String templateId) {
      int n = 0;
      for (RecognizedBuilding b : get(level).byMarker.values()) {
         if (b.active() && b.templateId().equals(templateId)) n++;
      }
      return n;
   }

   // ───── Persistence ─────

   @Override
   public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
      ListTag list = new ListTag();
      for (RecognizedBuilding b : byMarker.values()) list.add(b.save());
      tag.put("entries", list);
      return tag;
   }

   private static BuildingRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
      BuildingRegistry reg = new BuildingRegistry();
      if (!tag.contains("entries")) return reg;
      ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         try {
            RecognizedBuilding b = RecognizedBuilding.load(list.getCompound(i));
            // Tolerate stale templates (template removed in a mod
            // update). Drop them on load rather than crashing.
            if (BuildingTemplates.byId(b.templateId()).isPresent()) {
               reg.byMarker.put(b.markerPos().asLong(), b);
            }
         } catch (Throwable t) {
            VerboseLog.write("BUILDING_LOAD_SKIP",
               "index=" + i, "error=" + t);
         }
      }
      return reg;
   }

}

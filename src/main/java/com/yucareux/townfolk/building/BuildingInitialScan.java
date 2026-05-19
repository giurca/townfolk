package com.yucareux.townfolk.building;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * One-time migration scan that discovers pre-existing marker blocks
 * (beds out in the open, Charter Stones placed before Stage 10a, etc.)
 * and inserts them into the {@link BuildingRegistry}.
 *
 * <p>Without this, a world that pre-dates the building-recognition
 * system loads with empty BuildingRegistry, no homes recognized,
 * population cap = 0 — and the player would have to break + replace
 * every existing bed manually to trigger the lifecycle hook.
 *
 * <p>The scan runs ONCE per level per server session. It iterates
 * every position within each loaded town's coverage disk(s),
 * vertical band centred on the disk centre with ±48 blocks of
 * head/foot room. For each block that matches a building marker:
 *   - if not already in the registry → run the recognizer, insert
 *     with the right active/inactive flag
 *   - if already in the registry → skip
 *
 * <p>Cost: ~16384 columns × 96 y per 64-radius disk = ~1.5M
 * positions per town. ~150ms one-shot. Tolerable as a session-start
 * cost. Bigger towns or scan ranges may want time-slicing later.
 *
 * <p>Markers in unloaded chunks are skipped — they get picked up
 * when the chunk loads (a future enhancement: subscribe to
 * ChunkEvent.Load and rescan that chunk). For now the existing
 * place/break lifecycle hooks handle them once the player interacts.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class BuildingInitialScan {

   private BuildingInitialScan() {}

   /** Levels we've already scanned this session. Cleared automatically
    *  when the server shuts down (static state, fresh JVM = fresh set). */
   private static final Set<ResourceKey<Level>> scanned = new HashSet<>();

   private static final int Y_BELOW = 16;
   private static final int Y_ABOVE = 48;

   @SubscribeEvent
   public static void onLevelTick(LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      ResourceKey<Level> key = level.dimension();
      if (scanned.contains(key)) return;
      // Defer the scan a few ticks past world load so all chunks
      // around spawn are reliably loaded. The exact tick doesn't
      // matter; we just want "not the very first tick."
      if (level.getGameTime() < 100) return;
      scanned.add(key);
      runScan(level);
   }

   private static void runScan(ServerLevel level) {
      long startNs = System.nanoTime();
      int markersFound = 0, recognized = 0, alreadyRegistered = 0;
      BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();

      for (TownSquareBlockEntity town : TownSquareBlockEntity.loadedIn(level)) {
         var coverage = town.coverage();
         for (var disk : coverage.disks()) {
            BlockPos centre = disk.center();
            int r = disk.radius();
            long r2 = (long) r * r;
            int yMin = centre.getY() - Y_BELOW;
            int yMax = centre.getY() + Y_ABOVE;
            for (int x = centre.getX() - r; x <= centre.getX() + r; x++) {
               for (int z = centre.getZ() - r; z <= centre.getZ() + r; z++) {
                  long dx = (long) x - centre.getX();
                  long dz = (long) z - centre.getZ();
                  if (dx * dx + dz * dz > r2) continue;
                  // Skip unloaded chunks — would force-load them, expensive.
                  if (!level.isLoaded(cur.set(x, centre.getY(), z))) continue;
                  for (int y = yMin; y <= yMax; y++) {
                     cur.set(x, y, z);
                     Block block = level.getBlockState(cur).getBlock();
                     var tplOpt = BuildingTemplates.forMarker(block);
                     if (tplOpt.isEmpty()) continue;
                     markersFound++;
                     BlockPos pos = cur.immutable();
                     if (BuildingRegistry.findByMarker(level, pos).isPresent()) {
                        alreadyRegistered++;
                        continue;
                     }
                     var result = BuildingRecognizer.recognize(
                        level, pos, tplOpt.get(), BuildingOverride.NONE);
                     var entry = new RecognizedBuilding(
                        pos, tplOpt.get().id(), BuildingOverride.NONE,
                        result.boundary(), result.interior(),
                        result.floorArea(), result.height(),
                        level.getGameTime(), result.valid());
                     BuildingRegistry.put(level, entry);
                     if (result.valid()) recognized++;
                  }
               }
            }
         }
      }

      long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
      VerboseLog.write("BUILDING_INITIAL_SCAN",
         "level=" + level.dimension().location()
            + " markersFound=" + markersFound
            + " alreadyRegistered=" + alreadyRegistered
            + " newlyRecognized=" + recognized
            + " elapsedMs=" + elapsedMs, "");
   }
}

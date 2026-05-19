package com.yucareux.townfolk.building;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.diag.VerboseLog;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Glue between world block events and the {@link BuildingRegistry}.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li><b>On place:</b> if the placed block is a building marker
 *       ({@link BuildingTemplates#forMarker}), run the recognizer.
 *       Insert the result into the registry. Hint to the player
 *       what (if anything) the building is missing for activation.
 *   <li><b>On break or place near an existing building:</b> if the
 *       changed position is inside any registered building's
 *       interior or boundary, queue that building for
 *       re-validation. Re-validation runs through
 *       {@link BuildingRegistry#invalidate}.
 * </ol>
 *
 * <p>Dedup: the same building is invalidated at most once per tick
 * even if multiple blocks in its footprint change in one event burst
 * (e.g. an explosion). We track a {@link #pendingInvalidations} set
 * per-tick.
 *
 * <p>Lifecycle caveat: only PLAYER-driven block events fire here
 * (BlockEvent.BreakEvent / EntityPlaceEvent). Explosions, pistons,
 * world-edit-style replacements bypass and may leave stale registry
 * entries. Same trade-off StorageRegistryHooks made; consumers
 * (population cap, civic bonus check, etc.) iterate active=true
 * entries via {@link BuildingRegistry#countActiveOfType} and the
 * worst case is a phantom building lingering until something else
 * triggers re-validation.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class BuildingLifecycleHooks {

   private BuildingLifecycleHooks() {}

   /** Set of marker positions to re-validate on the next tick.
    *  Cleared every tick. Uses Long-packed positions to keep it
    *  cheap. */
   private static final Set<Long> pendingInvalidations = new HashSet<>();

   // ───── Place ─────

   @SubscribeEvent
   public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      BlockPos pos = event.getPos();
      Block placed = event.getPlacedBlock().getBlock();

      // (A) If this block is a marker, try to recognize a building
      // around it right now.
      var tplOpt = BuildingTemplates.forMarker(placed);
      if (tplOpt.isPresent()) {
         tryRecognize(level, pos, tplOpt.get(), event.getEntity());
      }

      // (B) If this block sits inside an existing building's
      // interior or boundary, queue that building for revalidation
      // — adding a wall or removing a hole may rescue or invalidate.
      queueAffectedRevalidations(level, pos);
   }

   // ───── Break ─────

   @SubscribeEvent
   public static void onBlockBreak(BlockEvent.BreakEvent event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      BlockPos pos = event.getPos();
      Block broken = event.getState().getBlock();

      // If the broken block is itself a marker, drop the registry
      // entry entirely — the building's anchor is gone.
      if (BuildingTemplates.forMarker(broken).isPresent()) {
         if (BuildingRegistry.findByMarker(level, pos).isPresent()) {
            BuildingRegistry.forget(level, pos);
            hint(event.getPlayer(),
               "Marker block broken — building removed.",
               ChatFormatting.YELLOW);
         }
         // Don't queue revalidation for OTHER buildings affected — a
         // marker can't be inside another building's footprint
         // unless something pathological. Skip the check for cost.
         return;
      }

      queueAffectedRevalidations(level, pos);
   }

   /** Walk every recognized building, queue any whose footprint
    *  includes {@code pos}. */
   private static void queueAffectedRevalidations(ServerLevel level, BlockPos pos) {
      var affected = BuildingRegistry.affectedBy(level, pos);
      for (var b : affected) {
         pendingInvalidations.add(b.markerPos().asLong());
      }
   }

   // ───── Tick — drain pending revalidations ─────

   @SubscribeEvent
   public static void onLevelTick(
         net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (pendingInvalidations.isEmpty()) return;
      // Take a snapshot and clear; invalidate() may itself indirectly
      // add to the set (it doesn't today, but defensive).
      long[] snapshot;
      synchronized (pendingInvalidations) {
         snapshot = new long[pendingInvalidations.size()];
         int i = 0;
         for (Long packed : pendingInvalidations) snapshot[i++] = packed;
         pendingInvalidations.clear();
      }
      for (long packed : snapshot) {
         BuildingRegistry.invalidate(level, BlockPos.of(packed));
      }
   }

   // ───── Helpers ─────

   /** Try to recognize a building anchored at {@code markerPos}.
    *  Always inserts a registry entry — active=true on success,
    *  active=false with a chat hint explaining the failure otherwise.
    *  This way the player gets immediate feedback when a marker is
    *  placed in a not-yet-finished room. */
   private static void tryRecognize(ServerLevel level, BlockPos markerPos,
                                     BuildingTemplate template,
                                     net.minecraft.world.entity.Entity placer) {
      // Honor any prior override from this marker pos — if the player
      // broke + replaced quickly, we want to keep their permit work.
      // For Stage 10a there's no permit, so this is always NONE.
      BuildingOverride override = BuildingRegistry.findByMarker(level, markerPos)
         .map(RecognizedBuilding::override)
         .orElse(BuildingOverride.NONE);

      var result = BuildingRecognizer.recognize(level, markerPos, template, override);
      long now = level.getGameTime();
      var entry = new RecognizedBuilding(
         markerPos, template.id(), override,
         result.boundary(), result.interior(),
         result.floorArea(), result.height(),
         now, result.valid());
      BuildingRegistry.put(level, entry);

      // Chat feedback to the placer.
      if (placer instanceof ServerPlayer sp) {
         if (result.valid()) {
            hint(sp,
               prettyTemplateName(template.id())
                  + " recognized — " + result.floorArea()
                  + " m², " + result.height() + " tall.",
               ChatFormatting.GREEN);
         } else {
            hint(sp,
               prettyTemplateName(template.id())
                  + " not yet a building: " + result.reason(),
               ChatFormatting.YELLOW);
         }
      }
      VerboseLog.write("BUILDING_RECOGNIZE_ATTEMPT",
         "pos=" + markerPos.toShortString()
            + " template=" + template.id()
            + " valid=" + result.valid()
            + " reason=" + result.reason(), "");
   }

   private static String prettyTemplateName(String id) {
      // Capitalize words, replace underscores with spaces.
      StringBuilder out = new StringBuilder(id.length());
      boolean nextUpper = true;
      for (char c : id.toCharArray()) {
         if (c == '_') { out.append(' '); nextUpper = true; }
         else if (nextUpper) { out.append(Character.toUpperCase(c)); nextUpper = false; }
         else out.append(c);
      }
      return out.toString();
   }

   private static void hint(net.minecraft.world.entity.player.Player p,
                            String text, ChatFormatting color) {
      if (p instanceof ServerPlayer sp) {
         sp.sendSystemMessage(Component.literal(text).withStyle(color), true);
      }
   }
}

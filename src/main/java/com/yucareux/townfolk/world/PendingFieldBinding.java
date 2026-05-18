package com.yucareux.townfolk.world;

import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.villager.FieldRegion;
import com.yucareux.townfolk.villager.LlmVillagerComponent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;

/**
 * Server-side state machine for the Surveyor's Stake binding flow.
 *
 *   1. Player right-clicks a townsfolk villager with stake equipped:
 *        {@link #beginBinding} stores the player → villager pair.
 *   2. Player right-clicks any block: first {@link #captureCorner} stamps
 *      cornerA; second call captures cornerB and validates the resulting
 *      {@link FieldRegion}. On success the binding moves into the
 *      TYPE-PENDING state and the client opens a popup to choose
 *      {@link FieldRegion.Type}.
 *   3. {@link #selectType} commits the parcel with the chosen type.
 *      Closing the popup without choosing (Escape) cancels the binding.
 *   4. Idle bindings auto-expire after {@link #MAX_AGE_TICKS} so a player
 *      who walks off mid-binding doesn't leave the slot wedged.
 *
 * Validation rejects:
 *   - parcels exceeding {@link FieldRegion#MAX_VOLUME}
 *   - parcels overlapping any existing parcel (regardless of owner) in
 *     the same town.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.yucareux.townfolk.Townfolk.MODID)
public final class PendingFieldBinding {

   /** Auto-expire stale bindings after 10 minutes of inaction. */
   public static final long MAX_AGE_TICKS = 20L * 60 * 10;

   public record State(UUID villagerUuid, BlockPos cornerA, long startedTick) {
      State withCorner(BlockPos a) { return new State(villagerUuid, a, startedTick); }
   }

   /** State held after both corners are captured and validated, while we
    *  wait for the client to choose a parcel type via the popup. */
   public record PendingType(UUID villagerUuid, FieldRegion candidate, long startedTick) {}

   private static final Map<UUID, State> PENDING = new ConcurrentHashMap<>();
   private static final Map<UUID, PendingType> TYPE_PENDING = new ConcurrentHashMap<>();

   /** Tick-driven scrubber for stale binding entries (audit item 6.3).
    *  Both maps are keyed by player UUID — without this, a player who
    *  disconnects mid-binding leaves an entry that never expires
    *  until they manually start over. */
   @net.neoforged.bus.api.SubscribeEvent
   public static void onServerTick(
         net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
      if (!(event.getLevel() instanceof ServerLevel sl)) return;
      // 1 Hz scrub — cheap, no need every tick.
      if (sl.getGameTime() % 20L != 0L) return;
      long now = sl.getGameTime();
      PENDING.entrySet().removeIf(e -> now - e.getValue().startedTick > MAX_AGE_TICKS);
      TYPE_PENDING.entrySet().removeIf(e -> now - e.getValue().startedTick > MAX_AGE_TICKS);
   }

   /** Right-click villager with stake equipped — start (or restart) a binding. */
   public static void beginBinding(ServerPlayer player, ServerLevel level, Villager villager) {
      // Restart-if-already-pending is intentional: clicking a second villager
      // mid-binding moves the binding target rather than refusing.
      State s = new State(villager.getUUID(), null, level.getGameTime());
      PENDING.put(player.getUUID(), s);
      String name = villager.hasCustomName() ? villager.getCustomName().getString() : "the villager";
      player.sendSystemMessage(Component.literal(
         "Binding to " + name + ". Right-click corner A of the parcel.")
         .withStyle(ChatFormatting.YELLOW), false);
      VerboseLog.write("STAKE_BEGIN", "player=" + player.getName().getString()
         + " villager=" + name, "");
   }

   /** Right-click block with stake equipped — capture a corner. Returns true
    *  if anything happened (so the caller can suppress block-place fallbacks). */
   public static boolean captureCorner(ServerPlayer player, ServerLevel level, BlockPos pos) {
      State s = PENDING.get(player.getUUID());
      if (s == null) return false;
      if (level.getGameTime() - s.startedTick > MAX_AGE_TICKS) {
         PENDING.remove(player.getUUID());
         player.sendSystemMessage(Component.literal(
            "Binding expired — start over by right-clicking the villager again.")
            .withStyle(ChatFormatting.RED), false);
         return true;
      }
      if (s.cornerA == null) {
         PENDING.put(player.getUUID(), s.withCorner(pos.immutable()));
         player.sendSystemMessage(Component.literal(
            "Corner A at " + pos.toShortString() + ". Right-click corner B.")
            .withStyle(ChatFormatting.YELLOW), false);
         return true;
      }
      // Second corner — try to complete.
      completeBinding(player, level, s, pos);
      PENDING.remove(player.getUUID());
      return true;
   }

   private static void completeBinding(ServerPlayer player, ServerLevel level,
                                       State s, BlockPos cornerB) {
      var ent = level.getEntity(s.villagerUuid);
      if (!(ent instanceof Villager v)) {
         player.sendSystemMessage(Component.literal(
            "The villager has wandered off — binding cancelled.")
            .withStyle(ChatFormatting.RED), false);
         return;
      }
      String id = UUID.randomUUID().toString().substring(0, 8);
      long day = level.getGameTime() / 24000L;
      FieldRegion candidate = new FieldRegion(id, s.cornerA, cornerB.immutable(), day, FieldRegion.Type.PLANT);

      if (candidate.volume() > FieldRegion.MAX_VOLUME) {
         player.sendSystemMessage(Component.literal(
            "Parcel too large (" + candidate.volume() + " blocks, max "
               + FieldRegion.MAX_VOLUME + "). Pick smaller corners.")
            .withStyle(ChatFormatting.RED), false);
         return;
      }

      // Overlap check across ALL townfolk in this level. Townfolk only —
      // vanilla villagers can't own parcels.
      for (var other : level.getAllEntities()) {
         if (!(other instanceof Villager ov)) continue;
         LlmVillagerComponent oc = ov.getData(ModRegistries.LLM_VILLAGER.get());
         for (FieldRegion existing : oc.parcels()) {
            if (existing.overlaps(candidate)) {
               String ownerName = ov.hasCustomName() ? ov.getCustomName().getString() : "another villager";
               player.sendSystemMessage(Component.literal(
                  "Overlaps " + ownerName + "'s parcel " + existing.shortLabel(s.cornerA)
                  + ". Pick a clear area.").withStyle(ChatFormatting.RED), false);
               return;
            }
         }
      }

      // Validation passed — move to TYPE-PENDING and ask the client to
      // pick a parcel type via the popup. The parcel is NOT yet on the
      // villager; commit happens in selectType().
      TYPE_PENDING.put(player.getUUID(),
         new PendingType(s.villagerUuid, candidate, level.getGameTime()));
      net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
         new com.yucareux.townfolk.network.OpenParcelTypePopupPayload(
            candidate.sizeX(), candidate.sizeZ()));
      String name = v.hasCustomName() ? v.getCustomName().getString() : "the villager";
      player.sendSystemMessage(Component.literal(
         "Parcel boundary set (" + candidate.sizeX() + "×" + candidate.sizeZ()
            + "). Pick what " + name + " will do here.")
         .withStyle(ChatFormatting.YELLOW), false);
      VerboseLog.write("STAKE_TYPE_PENDING", "player=" + player.getName().getString()
         + " villager=" + name + " parcel=" + id,
         candidate.minCorner().toShortString() + " " + candidate.maxCorner().toShortString());
   }

   /** Client-side popup picked a type. Commit the parcel.
    *
    *  For PLANT parcels, the crop-plan editor opens immediately after
    *  commit so the player can set the desired crop mix (the parcel
    *  defaults to {@link com.yucareux.townfolk.town.CropPlan#DEFAULT}
    *  if the player closes without changes — wheat at weight 1, same
    *  as the pre-plan behaviour). */
   public static void selectType(ServerPlayer player, FieldRegion.Type type) {
      PendingType pt = TYPE_PENDING.remove(player.getUUID());
      if (pt == null) return;            // popup closed / stale
      ServerLevel level = player.serverLevel();
      var ent = level.getEntity(pt.villagerUuid);
      if (!(ent instanceof Villager v)) {
         player.sendSystemMessage(Component.literal(
            "The villager has wandered off — binding cancelled.")
            .withStyle(ChatFormatting.RED), false);
         return;
      }
      FieldRegion typed = new FieldRegion(pt.candidate.id(), pt.candidate.cornerA(),
         pt.candidate.cornerB(), pt.candidate.createdDay(), type);
      LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
      v.setData(ModRegistries.LLM_VILLAGER.get(), comp.withAppendedParcel(typed));
      String name = v.hasCustomName() ? v.getCustomName().getString() : "the villager";
      player.sendSystemMessage(Component.literal(
         name + "'s " + type.label().toLowerCase(java.util.Locale.ROOT) + " parcel set: "
            + typed.sizeX() + "×" + typed.sizeZ()
            + " at " + typed.minCorner().toShortString()
            + " — " + typed.maxCorner().toShortString())
         .withStyle(ChatFormatting.GREEN), false);
      VerboseLog.write("STAKE_COMPLETE", "player=" + player.getName().getString()
         + " villager=" + name + " parcel=" + typed.id() + " type=" + type,
         typed.minCorner().toShortString() + " " + typed.maxCorner().toShortString());

      // Open the crop-plan editor immediately for PLANT parcels so the
      // player can set the mix while the parcel is fresh. Skips for
      // ANIMAL — we don't have an animal-task plan UI yet.
      if (type == FieldRegion.Type.PLANT) {
         openCropPlanScreen(player, typed.id());
      }
   }

   /** Server-side: open the crop-plan editor menu on the given player
    *  for the given parcel id. Used both by the post-create flow above
    *  and the "edit existing parcel" stake-reopen path. */
   public static void openCropPlanScreen(ServerPlayer player, String parcelId) {
      final String pid = parcelId == null ? "" : parcelId;
      player.openMenu(
         new net.minecraft.world.SimpleMenuProvider(
            (id, inv, p) -> new com.yucareux.townfolk.world.inventory.CropPlanMenu(id, inv, pid),
            Component.literal("Plant plan")),
         buf -> buf.writeUtf(pid, 64));
      VerboseLog.write("CROP_PLAN_OPEN", "player=" + player.getName().getString()
         + " parcel=" + pid, "");
   }

   /** Find which villager owns the parcel containing the given block.
    *  Returns null if no townsfolk has a parcel here (or its owner
    *  isn't loaded). Read-only; used by the stake's "edit existing
    *  parcel" path. */
   public static FieldRegion parcelAt(ServerLevel level, net.minecraft.core.BlockPos at) {
      for (var town : com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(level)) {
         for (var entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            var ent = level.getEntity(entry.uuid());
            if (!(ent instanceof Villager v)) continue;
            LlmVillagerComponent c = v.getData(ModRegistries.LLM_VILLAGER.get());
            for (FieldRegion p : c.parcels()) {
               if (p.contains(at)) return p;
            }
         }
      }
      return null;
   }

   /** Client-side popup closed without picking (Escape) — drop the
    *  pending parcel. */
   public static void cancelTypeSelection(ServerPlayer player) {
      PendingType pt = TYPE_PENDING.remove(player.getUUID());
      if (pt == null) return;
      player.sendSystemMessage(Component.literal(
         "Parcel creation cancelled.")
         .withStyle(ChatFormatting.GRAY), false);
      VerboseLog.write("STAKE_CANCEL", "player=" + player.getName().getString()
         + " parcel=" + pt.candidate.id(), "type selection cancelled");
   }

   /** Sneak-right-click villager with stake while standing in a parcel —
    *  unbind that parcel. Returns true if a parcel was removed. */
   public static boolean unbindParcelAt(ServerPlayer player, Villager villager, BlockPos at) {
      LlmVillagerComponent comp = villager.getData(ModRegistries.LLM_VILLAGER.get());
      FieldRegion match = null;
      for (FieldRegion p : comp.parcels()) {
         if (p.contains(at)) { match = p; break; }
      }
      if (match == null) {
         String name = villager.hasCustomName() ? villager.getCustomName().getString() : "the villager";
         player.sendSystemMessage(Component.literal(
            "You're not standing inside any of " + name + "'s parcels.")
            .withStyle(ChatFormatting.GRAY), false);
         return false;
      }
      villager.setData(ModRegistries.LLM_VILLAGER.get(), comp.withoutParcel(match.id()));
      // Cancel any block task whose target lies in the removed parcel.
      BlockTaskQueue.cancel(villager.getUUID());
      // Drop the per-parcel plans (PLANT-side crop plan and
      // ANIMAL-side breed-up plan) so the registries don't accumulate
      // dead entries every time a parcel is rebuilt.
      if (player.serverLevel() != null) {
         com.yucareux.townfolk.town.CropPlanRegistry.forget(player.serverLevel(), match.id());
         com.yucareux.townfolk.town.AnimalPlanRegistry.forget(player.serverLevel(), match.id());
      }
      String name = villager.hasCustomName() ? villager.getCustomName().getString() : "the villager";
      player.sendSystemMessage(Component.literal(
         "Unbound " + name + "'s parcel " + match.shortLabel(at))
         .withStyle(ChatFormatting.YELLOW), false);
      VerboseLog.write("STAKE_UNBIND", "player=" + player.getName().getString()
         + " villager=" + name + " parcel=" + match.id(), match.minCorner().toShortString());
      return true;
   }

   /** Sneak-right-click a block in any parcel — find whichever loaded
    *  townsfolk owns that parcel and unbind it, no need to track down
    *  the villager. Returns true if a parcel was found and unbound. */
   public static boolean unbindParcelByBlock(ServerPlayer player, ServerLevel level, BlockPos at) {
      // Walk every loaded town's villager list. The component (and thus
      // the parcel list) lives on the entity, so the villager must be
      // loaded to inspect — usually fine since the player is standing on
      // the parcel, which means a nearby chunk is loaded and a working
      // villager will be nearby too.
      for (var town : com.yucareux.townfolk.blockentity.TownSquareBlockEntity.loadedIn(level)) {
         for (var entry : town.getTown().villagers()) {
            if (!entry.alive()) continue;
            var ent = level.getEntity(entry.uuid());
            if (!(ent instanceof Villager v)) continue;
            LlmVillagerComponent comp = v.getData(ModRegistries.LLM_VILLAGER.get());
            for (FieldRegion p : comp.parcels()) {
               if (p.contains(at)) {
                  return unbindParcelAt(player, v, at);
               }
            }
         }
      }
      player.sendSystemMessage(Component.literal(
         "No villager owns a parcel at this block (or its owner isn't loaded).")
         .withStyle(ChatFormatting.GRAY), false);
      return false;
   }

   public static boolean hasPending(UUID player) { return PENDING.containsKey(player); }
   public static void clear(UUID player) { PENDING.remove(player); }

   private PendingFieldBinding() {}
}

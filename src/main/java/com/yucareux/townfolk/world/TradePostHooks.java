package com.yucareux.townfolk.world;

import com.yucareux.townfolk.Townfolk;
import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.blockentity.TradePostBlockEntity;
import com.yucareux.townfolk.registry.ModRegistries;
import com.yucareux.townfolk.town.TownAuxiliaryEntry;
import com.yucareux.townfolk.town.TownAuxiliaryType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Lifecycle glue for {@link com.yucareux.townfolk.block.TradePostBlock}.
 *
 * <p><b>On place:</b> verify the block sits inside some town's coverage,
 * pick the nearest such town, register the post as an
 * {@link TownAuxiliaryType#TRADE_POST} auxiliary on that town, and write
 * the owning town's pos onto the {@link TradePostBlockEntity}. If no
 * town claims the spot, cancel the place and refund the item with a
 * chat hint.
 *
 * <p><b>On break:</b> if the block was a registered Trade Post, walk
 * every loaded town and deregister it. Cheap (loaded-town count is
 * typically 1 or 2).
 *
 * <p>Matches the same Block break path used by
 * {@link StorageRegistryHooks} — only player breaks fire the event;
 * explosions and pistons leave an orphan registry entry behind. Tolerable
 * because every consumer of the coverage list iterates current entries
 * each call, and a broken auxiliary just contributes a phantom disk
 * over empty space until the next reload. Fully cleaning that up is
 * Stage 6 polish, not blocking here.
 */
@EventBusSubscriber(modid = Townfolk.MODID)
public final class TradePostHooks {

   private TradePostHooks() {}

   /** Server-side placement: bind to an owning town or refuse. */
   @SubscribeEvent
   public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (event.getPlacedBlock().getBlock() != ModRegistries.TRADE_POST_BLOCK.get()) return;

      BlockPos pos = event.getPos();
      TownSquareBlockEntity owner = nearestCoveringTown(level, pos);
      if (owner == null) {
         // No town claims this spot. Cancel the place; the item stack
         // is returned to the placer automatically by the cancel.
         event.setCanceled(true);
         if (event.getEntity() instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(
                  "A Trade Post must be placed inside an existing town.")
               .withStyle(ChatFormatting.YELLOW), true);
         }
         com.yucareux.townfolk.diag.VerboseLog.write("TRADE_POST_PLACE_REJECT",
            "pos=" + pos.toShortString(), "no town coverage at place site");
         return;
      }

      // Register as an auxiliary on the owning town.
      long nowTick = level.getGameTime();
      owner.getTown().addAuxiliary(new TownAuxiliaryEntry(
         pos, TownAuxiliaryType.TRADE_POST, nowTick));
      owner.setChanged();

      // Stamp the owner on the post's BE so we can look it up cheaply
      // on right-click and on break without re-scanning every loaded town.
      if (level.getBlockEntity(pos) instanceof TradePostBlockEntity tpe) {
         tpe.setOwnerTownPos(owner.getBlockPos().asLong());
      }

      com.yucareux.townfolk.diag.VerboseLog.write("TRADE_POST_PLACED",
         "pos=" + pos.toShortString()
            + " town=" + owner.getBlockPos().toShortString()
            + " townName=" + owner.getTown().townName(),
         "");

      if (event.getEntity() instanceof ServerPlayer sp) {
         sp.sendSystemMessage(Component.literal(
               "Trade Post bound to " + owner.getTown().townName()
               + " — town coverage extended by "
               + TownAuxiliaryType.TRADE_POST.contributedRadius() + " blocks.")
            .withStyle(ChatFormatting.GREEN), true);
      }
   }

   /** Server-side break: drop the auxiliary registration. */
   @SubscribeEvent
   public static void onBlockBreak(BlockEvent.BreakEvent event) {
      if (!(event.getLevel() instanceof ServerLevel level)) return;
      if (event.getState().getBlock() != ModRegistries.TRADE_POST_BLOCK.get()) return;

      BlockPos pos = event.getPos();
      TownSquareBlockEntity owner = ownerOf(level, pos);
      if (owner == null) {
         // Orphan (placed before this hook existed, or town unloaded).
         // Nothing to deregister; let the break proceed silently.
         return;
      }
      boolean removed = owner.getTown().removeAuxiliaryAt(pos);
      if (removed) {
         owner.setChanged();
         com.yucareux.townfolk.diag.VerboseLog.write("TRADE_POST_BROKEN",
            "pos=" + pos.toShortString()
               + " town=" + owner.getBlockPos().toShortString(), "");
      }
   }

   /** Find which town owns the Trade Post at {@code pos}. Tries the
    *  cached ownerTownPos on the BE first, falls back to coverage
    *  scan. Returns {@code null} if no owning town is loaded. */
   private static TownSquareBlockEntity ownerOf(ServerLevel level, BlockPos pos) {
      if (level.getBlockEntity(pos) instanceof TradePostBlockEntity tpe
          && tpe.ownerTownPos() != 0L) {
         BlockPos townPos = BlockPos.of(tpe.ownerTownPos());
         if (level.getBlockEntity(townPos) instanceof TownSquareBlockEntity ts) {
            return ts;
         }
      }
      // Fallback: route through TownCoverage like every other
      // "which town contains this pos?" callsite in the codebase
      // (Stage 1.2 migration). At break time the aux entry usually
      // still exists, so the matching town's coverage trivially
      // contains the broken pos via its own auxiliary disk —
      // exactly what we want. Tie-break by master-block distance.
      TownSquareBlockEntity best = null;
      long bestDsq = Long.MAX_VALUE;
      for (TownSquareBlockEntity ts : TownSquareBlockEntity.loadedIn(level)) {
         if (!ts.coverage().contains(pos)) continue;
         long dx = (long) pos.getX() - ts.getBlockPos().getX();
         long dz = (long) pos.getZ() - ts.getBlockPos().getZ();
         long dsq = dx * dx + dz * dz;
         if (dsq < bestDsq) { bestDsq = dsq; best = ts; }
      }
      return best;
   }

   /** Pick the loaded town whose CURRENT coverage contains {@code pos},
    *  preferring the one whose master block is nearest. Used at place
    *  time when the aux entry doesn't exist yet. */
   private static TownSquareBlockEntity nearestCoveringTown(ServerLevel level, BlockPos pos) {
      TownSquareBlockEntity best = null;
      long bestDsq = Long.MAX_VALUE;
      for (TownSquareBlockEntity ts : TownSquareBlockEntity.loadedIn(level)) {
         if (!ts.coverage().contains(pos)) continue;
         long dx = (long) pos.getX() - ts.getBlockPos().getX();
         long dz = (long) pos.getZ() - ts.getBlockPos().getZ();
         long dsq = dx * dx + dz * dz;
         if (dsq < bestDsq) { bestDsq = dsq; best = ts; }
      }
      return best;
   }
}

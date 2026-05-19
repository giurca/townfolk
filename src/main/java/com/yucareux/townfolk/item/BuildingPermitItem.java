package com.yucareux.townfolk.item;

import com.yucareux.townfolk.building.BuildingRecognizer;
import com.yucareux.townfolk.building.BuildingRegistry;
import com.yucareux.townfolk.building.BuildingTemplates;
import com.yucareux.townfolk.building.RecognizedBuilding;
import com.yucareux.townfolk.network.OpenBuildingPermitPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Building Permit — single-use document for overriding the volume /
 * height caps on a specific recognized (or-soon-to-be-recognized)
 * building.
 *
 * <p>Right-click any building marker block (Charter Stone, bed,
 * future workshop / library markers) while holding this. The server:
 * <ol>
 *   <li>Confirms the clicked block is a registered building marker.
 *   <li>Builds a snapshot of the building's current state — current
 *       cap values, current measured volume / height, validation
 *       status.
 *   <li>Sends {@link OpenBuildingPermitPayload} to the player; the
 *       client opens
 *       {@code BuildingPermitScreen} with that snapshot.
 * </ol>
 *
 * <p>Critically: the permit is NOT consumed at right-click time. It's
 * only consumed when the player clicks Apply in the screen and the
 * server actually writes a new override. Right-clicking the wrong
 * block, opening the screen and cancelling, or being denied by the
 * server (auth, marker disappeared, etc.) all leave the permit
 * intact.
 *
 * <p>If the clicked block is NOT a marker, this returns
 * {@link InteractionResult#PASS} so vanilla right-click behaviour
 * still works (e.g. right-clicking a chest with a permit in hand
 * opens the chest — the permit only "engages" against marker blocks).
 */
public class BuildingPermitItem extends Item {

   public BuildingPermitItem(Properties props) {
      super(props);
   }

   @Override
   public InteractionResult useOn(UseOnContext ctx) {
      // Client-side: short-circuit so we don't double-act. The
      // permit's actual effect runs on the server below.
      if (ctx.getLevel().isClientSide) {
         BlockPos pos = ctx.getClickedPos();
         Block block = ctx.getLevel().getBlockState(pos).getBlock();
         // Return CONSUME for marker blocks so the swing animation
         // fires; PASS otherwise so the player can still interact
         // with the block normally.
         return BuildingTemplates.forMarker(block).isPresent()
            ? InteractionResult.CONSUME
            : InteractionResult.PASS;
      }
      if (!(ctx.getPlayer() instanceof ServerPlayer sp)) return InteractionResult.PASS;
      if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.PASS;

      BlockPos pos = ctx.getClickedPos();
      Block block = level.getBlockState(pos).getBlock();
      var tplOpt = BuildingTemplates.forMarker(block);
      if (tplOpt.isEmpty()) {
         // Not a building marker — let other interactions through.
         return InteractionResult.PASS;
      }
      var template = tplOpt.get();

      // Find or synthesize a recognition entry for this marker. If the
      // marker hasn't been recognized yet (e.g. an old bed pre-Stage
      // 10a), run a one-shot recognition now so the popup has accurate
      // current-measurement readings.
      RecognizedBuilding existing = BuildingRegistry.findByMarker(level, pos).orElse(null);
      var override = existing != null ? existing.override()
         : com.yucareux.townfolk.building.BuildingOverride.NONE;
      var result = BuildingRecognizer.recognize(level, pos, template, override);

      // If there was no existing entry, persist what we just measured
      // so the apply step has something to update. Active flag matches
      // the recognition result.
      if (existing == null) {
         long now = level.getGameTime();
         existing = new RecognizedBuilding(
            pos.immutable(), template.id(), override,
            result.boundary(), result.interior(),
            result.floorArea(), result.height(),
            now, result.valid());
         BuildingRegistry.put(level, existing);
      }

      // Currently-measured volume / height — read off the recognition
      // result so the popup tells the player exactly where they stand.
      int measuredVolume = result.interior() == null ? 0 : result.interior().size();
      int measuredHeight = result.height();
      int currentMaxVol  = override.effectiveMaxVolume(template);
      int currentMaxHgt  = override.effectiveMaxHeight(template);

      PacketDistributor.sendToPlayer(sp, new OpenBuildingPermitPayload(
         pos.asLong(),
         template.id(),
         override.maxVolume(),
         override.maxHeight(),
         currentMaxVol,
         currentMaxHgt,
         measuredVolume,
         measuredHeight,
         template.maxVolume(),
         template.maxHeight(),
         result.valid(),
         result.reason() == null ? "" : result.reason()
      ));
      sp.sendSystemMessage(Component.literal(
            "Reviewing permit for " + prettyName(template.id()) + "…")
         .withStyle(ChatFormatting.GRAY), true);
      com.yucareux.townfolk.diag.VerboseLog.write("PERMIT_REVIEW",
         "player=" + sp.getName().getString()
            + " pos=" + pos.toShortString()
            + " template=" + template.id()
            + " valid=" + result.valid(), "");

      return InteractionResult.CONSUME;     // permit NOT consumed yet — only on Apply
   }

   private static String prettyName(String id) {
      StringBuilder out = new StringBuilder(id.length());
      boolean cap = true;
      for (char c : id.toCharArray()) {
         if (c == '_') { out.append(' '); cap = true; }
         else if (cap) { out.append(Character.toUpperCase(c)); cap = false; }
         else out.append(c);
      }
      return out.toString();
   }
}

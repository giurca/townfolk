package com.yucareux.townfolk.item;

import com.yucareux.townfolk.world.PendingFieldBinding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The Surveyor's Stake. Held tool used to assign parcels of land to a
 * townsfolk villager. The flow lives in {@link PendingFieldBinding}; this
 * item just routes:
 *
 *   right-click block + binding pending → capture corner
 *   right-click block + no binding      → no-op (don't accidentally place
 *                                          anything, don't crash if used
 *                                          alone)
 *
 * Right-click on a villager is handled in
 * {@link com.yucareux.townfolk.event.InteractHandler} so we don't fight
 * the existing dialogue / gift interception logic.
 *
 * No durability, no NBT, no block placement. Just an interaction conduit.
 */
public class SurveyorStakeItem extends Item {

   public SurveyorStakeItem(Properties props) {
      super(props);
   }

   @Override
   public InteractionResult useOn(UseOnContext ctx) {
      if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
      if (!(ctx.getPlayer() instanceof ServerPlayer sp)) return InteractionResult.PASS;
      if (!(ctx.getLevel() instanceof ServerLevel sl)) return InteractionResult.PASS;

      // Sneak + right-click a block: unbind whichever parcel contains
      // that block, from whichever villager owns it. No need to find
      // the villager — the stake locates them. Matches the existing
      // sneak-right-click-villager behaviour but works from any block in
      // the parcel.
      if (sp.isShiftKeyDown()) {
         PendingFieldBinding.unbindParcelByBlock(sp, sl, ctx.getClickedPos());
         return InteractionResult.CONSUME;
      }

      boolean handled = PendingFieldBinding.captureCorner(sp, sl, ctx.getClickedPos());
      if (handled) return InteractionResult.CONSUME;

      // No binding was pending. If this block lies in an existing
      // PLANT parcel, re-open the crop-plan editor for it. This is
      // the management path that lets the player tweak the mix on an
      // already-created parcel without having to unbind + rebind.
      var existing = PendingFieldBinding.parcelAt(sl, ctx.getClickedPos());
      if (existing != null) {
         if (existing.type() == com.yucareux.townfolk.villager.FieldRegion.Type.PLANT) {
            PendingFieldBinding.openCropPlanScreen(sp, existing.id());
            return InteractionResult.CONSUME;
         }
         // ANIMAL — no editor yet. Tell the player what to do
         // instead of silently consuming the click.
         sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(
               "This is an animal parcel — no plan editor yet. Sneak+right-click to unbind.")
            .withStyle(net.minecraft.ChatFormatting.GRAY), false);
         return InteractionResult.CONSUME;
      }
      // Not in any parcel. Just consume so vanilla doesn't try to
      // place anything.
      return InteractionResult.SUCCESS;
   }
}

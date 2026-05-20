package com.yucareux.townfolk.world.verbs;

import com.yucareux.townfolk.world.ActionFeedback;
import com.yucareux.townfolk.world.RecipeCatalog;
import com.yucareux.townfolk.world.ToolDispatcher.VerbContext;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code [ACTION: place [block-hint]]} — pick a placeable
 * {@link BlockItem} (from hint, or auto-picked from the villager's
 * bag) and place it in front of them on a solid surface. Search
 * order for an air slot in front: same Y, +1, -1, +2.
 *
 * <p>Extracted from {@code ToolDispatcher.doPlace} in stage 17a.4.
 */
public final class PlaceVerb {

   private PlaceVerb() {}

   public static void run(VerbContext ctx) {
      String h = ctx.body().toLowerCase(Locale.ROOT);
      Item item = h.isEmpty() ? null : RecipeCatalog.resolveItemOpt(h).orElse(null);
      var inv = ctx.actor().getInventory();
      if (item == null) {
         for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BlockItem) {
               item = s.getItem(); break;
            }
         }
      }
      if (item == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "place — nothing placeable in your bag");
         return;
      }
      if (!(item instanceof BlockItem blockItem)) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "place — " + h + " isn't a placeable block");
         return;
      }
      Direction facing = ctx.actor().getDirection();
      BlockPos infront = ctx.actor().blockPosition().relative(facing);
      BlockPos placeAt = null;
      for (int dy : new int[]{0, 1, -1, 2}) {
         BlockPos test = infront.offset(0, dy, 0);
         if (ctx.level().getBlockState(test).isAir()
             && !ctx.level().getBlockState(test.below()).isAir()) {
            placeAt = test; break;
         }
      }
      if (placeAt == null) {
         ActionFeedback.recordFail(ctx.actor().getUUID(), ctx.level().getGameTime(),
            "place — no clear space in front of me to put it");
         return;
      }
      final BlockPos finalPlaceAt = placeAt;
      final Item finalItem = item;
      BlockTaskHelpers.enqueueBlockTask(ctx.level(), ctx.actor(), infront, "place", (lvl, v, pos) -> {
         var inv2 = v.getInventory();
         int slot = -1;
         for (int i = 0; i < inv2.getContainerSize(); i++) {
            if (inv2.getItem(i).getItem() == finalItem) { slot = i; break; }
         }
         if (slot < 0) throw new RuntimeException("block gone from inventory before placing");
         lvl.setBlockAndUpdate(finalPlaceAt, blockItem.getBlock().defaultBlockState());
         var stack = inv2.getItem(slot);
         stack.shrink(1);
         if (stack.isEmpty()) inv2.setItem(slot, ItemStack.EMPTY);
         return "placed " + StorageHelpers.shortName(BuiltInRegistries.ITEM.getKey(finalItem).toString())
            + " at " + finalPlaceAt.toShortString();
      });
   }
}

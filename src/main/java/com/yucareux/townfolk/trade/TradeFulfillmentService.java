package com.yucareux.townfolk.trade;

import com.yucareux.townfolk.blockentity.TownSquareBlockEntity;
import com.yucareux.townfolk.diag.VerboseLog;
import com.yucareux.townfolk.town.TownData;
import com.yucareux.townfolk.town.TownLog;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server-side handler for [Deliver] on a Trade tab offer.
 *
 * <p>Validates:
 * <ul>
 *   <li>The owning town BE exists at {@code townSquarePos}.
 *   <li>The offer id exists, is active, and hasn't expired.
 *   <li>The player has at least {@code requestCount} of the requested
 *       item in their inventory.
 * </ul>
 *
 * <p>On success: consumes the items, gives emeralds, applies the
 * tier's prestige delta, marks the offer fulfilled, logs to TownLog +
 * VerboseLog, and pushes a fresh admin-state update so the player's
 * Trade tab refreshes immediately.
 *
 * <p>Authorisation (player must be near the town / op / singleplayer)
 * is enforced at the network entry point — this handler trusts its
 * caller.
 */
public final class TradeFulfillmentService {

   private TradeFulfillmentService() {}

   public static void handle(ServerPlayer sp, ServerLevel level,
                              BlockPos townPos, String offerId) {
      var be = level.getBlockEntity(townPos);
      if (!(be instanceof TownSquareBlockEntity town)) {
         hint(sp, "That town no longer exists.", ChatFormatting.RED);
         return;
      }
      TownData data = town.getTown();
      var maybe = data.findTradeOffer(offerId);
      if (maybe.isEmpty()) {
         hint(sp, "That offer is no longer available.", ChatFormatting.YELLOW);
         pushRefresh(sp, level, town);
         return;
      }
      TradeOffer offer = maybe.get();
      if (!offer.isActive()) {
         hint(sp, "That offer has already been " + offer.status() + ".", ChatFormatting.YELLOW);
         pushRefresh(sp, level, town);
         return;
      }
      long currentDay = level.getDayTime() / 24000L;
      if (currentDay > offer.expireDay()) {
         hint(sp, "That offer expired before you could deliver.", ChatFormatting.YELLOW);
         // Don't mark expired here — the daily tick handles that.
         return;
      }

      // Resolve the requested item.
      ResourceLocation rl = ResourceLocation.tryParse(offer.requestItemId());
      if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
         hint(sp, "Offer references an unknown item — please report this.", ChatFormatting.RED);
         return;
      }
      Item want = BuiltInRegistries.ITEM.get(rl);

      // Count availability before mutating.
      Inventory inv = sp.getInventory();
      int have = 0;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() == want) have += s.getCount();
      }
      if (have < offer.requestCount()) {
         hint(sp, "You need " + offer.requestCount() + "× " + shortName(offer.requestItemId())
            + " — you have " + have + ".", ChatFormatting.YELLOW);
         return;
      }

      // Consume.
      int need = offer.requestCount();
      for (int i = 0; i < inv.getContainerSize() && need > 0; i++) {
         ItemStack s = inv.getItem(i);
         if (s.getItem() != want) continue;
         int take = Math.min(need, s.getCount());
         s.shrink(take);
         need -= take;
      }
      inv.setChanged();

      // Pay.
      ItemStack payment = new ItemStack(Items.EMERALD, offer.paymentEmeralds());
      if (!inv.add(payment)) {
         // Inventory full — drop the rest at the player's feet.
         sp.drop(payment, false);
      }

      // Mark fulfilled, apply prestige.
      data.replaceTradeOffer(offer.withStatus(TradeOffer.STATUS_FULFILLED));
      int delta = offer.tier().prestigeOnFulfill;
      int afterPrestige = data.addPrestige(delta);
      town.setChanged();

      data.log().add(level.getGameTime(), TownLog.Level.INFO,
         sp.getName().getString() + " fulfilled " + offer.archetype().displayName()
            + "'s offer: " + offer.requestCount() + "× " + shortName(offer.requestItemId())
            + " → " + offer.paymentEmeralds() + " emeralds. Prestige +"
            + delta + " → " + afterPrestige + ".");
      VerboseLog.write("TRADE_FULFILLED",
         "player=" + sp.getName().getString()
            + " town=" + data.townName()
            + " offer=" + offer.id()
            + " item=" + offer.requestItemId()
            + " count=" + offer.requestCount()
            + " paid=" + offer.paymentEmeralds()
            + " prestigeAfter=" + afterPrestige, "");

      hint(sp, "Delivered. +" + offer.paymentEmeralds() + " emeralds. Prestige "
         + afterPrestige + "/" + TownData.MAX_PRESTIGE + ".", ChatFormatting.GREEN);

      pushRefresh(sp, level, town);
   }

   private static void pushRefresh(ServerPlayer sp, ServerLevel level, TownSquareBlockEntity town) {
      com.yucareux.townfolk.dialogue.TownAdminService.openAdminPanel(sp, level, town);
   }

   private static void hint(ServerPlayer sp, String text, ChatFormatting color) {
      sp.sendSystemMessage(Component.literal(text).withStyle(color), true);
   }

   private static String shortName(String itemId) {
      int c = itemId.indexOf(':');
      return (c < 0 ? itemId : itemId.substring(c + 1)).replace('_', ' ');
   }
}

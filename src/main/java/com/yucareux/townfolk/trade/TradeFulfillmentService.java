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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side handler for [Deliver] on a Trade tab offer.
 *
 * <p>Sourcing + payment model (Stage 8 redesign):
 * <ul>
 *   <li>Request items are pulled from the <b>town's storage</b> —
 *       every registered container within the town's coverage. The
 *       player is the chancellor sealing the deal, not the courier.
 *   <li>Payment is deposited into the <b>town treasury</b> — an
 *       abstract item-count pool on {@link TownData}, not tied to any
 *       physical container. Solves the "all barrels full, payment has
 *       nowhere to go" problem. Player withdraws from the treasury
 *       through the Trade tab.
 * </ul>
 *
 * <p>Validation order:
 * <ol>
 *   <li>Town BE exists.
 *   <li>Offer id exists, is active, hasn't expired.
 *   <li>Town storage has ≥ requestCount of the requested item.
 * </ol>
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

      // Count availability across the town's coverage. Reads live BE
      // contents so we never act on stale snapshot data.
      int available = countInTown(level, town, want);
      if (available < offer.requestCount()) {
         hint(sp, "Town needs " + offer.requestCount() + "× " + shortName(offer.requestItemId())
            + " — only " + available + " in storage.", ChatFormatting.YELLOW);
         return;
      }

      // Consume from town storage.
      int actuallyTook = consumeFromTown(level, town, want, offer.requestCount());
      if (actuallyTook < offer.requestCount()) {
         // Shouldn't happen — count said we had enough. Roll back the
         // partial take by spawning what we already removed as item
         // entities in front of the player, then bail.
         if (actuallyTook > 0) {
            ItemStack refund = new ItemStack(want, actuallyTook);
            sp.drop(refund, false);
         }
         hint(sp, "Town storage changed mid-deal. Try again.", ChatFormatting.YELLOW);
         pushRefresh(sp, level, town);
         return;
      }

      // Pay into the magical treasury — NOT into the player's bag
      // and NOT into a physical barrel. Player pulls it out later via
      // the Trade tab.
      data.depositToTreasury("minecraft:emerald", offer.paymentEmeralds());

      // Mark fulfilled, apply prestige.
      data.replaceTradeOffer(offer.withStatus(TradeOffer.STATUS_FULFILLED));
      int delta = offer.tier().prestigeOnFulfill;
      int afterPrestige = data.addPrestige(delta);
      town.setChanged();

      data.log().add(level.getGameTime(), TownLog.Level.INFO,
         "Fulfilled " + offer.archetype().displayName() + "'s offer: "
            + offer.requestCount() + "× " + shortName(offer.requestItemId())
            + " → " + offer.paymentEmeralds() + " emeralds to treasury. "
            + "Prestige +" + delta + " → " + afterPrestige + ".");
      VerboseLog.write("TRADE_FULFILLED",
         "player=" + sp.getName().getString()
            + " town=" + data.townName()
            + " offer=" + offer.id()
            + " item=" + offer.requestItemId()
            + " count=" + offer.requestCount()
            + " paid=" + offer.paymentEmeralds()
            + " prestigeAfter=" + afterPrestige, "");

      hint(sp, "Delivered. " + offer.paymentEmeralds() + " emeralds added to town treasury. "
         + "Prestige " + afterPrestige + "/" + TownData.MAX_PRESTIGE + ".",
         ChatFormatting.GREEN);

      pushRefresh(sp, level, town);
   }

   /** Count how many of {@code want} are currently in registered
    *  containers inside this town's coverage. Reads live BE contents;
    *  containers outside coverage are ignored. */
   static int countInTown(ServerLevel level, TownSquareBlockEntity town, Item want) {
      int total = 0;
      var coverage = town.coverage();
      for (var entry : com.yucareux.townfolk.town.StorageRegistry.entries(level)) {
         BlockPos pos = BlockPos.of(entry.getKey());
         if (!coverage.contains(pos)) continue;
         var be = level.getBlockEntity(pos);
         if (!(be instanceof net.minecraft.world.Container c)) continue;
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.getItem() == want) total += s.getCount();
         }
      }
      return total;
   }

   /** Remove up to {@code need} of {@code want} from town storage.
    *  Returns the amount actually removed (may be less if a barrel
    *  vanished mid-walk). Walks every registered container in the
    *  town's coverage in registry order. */
   static int consumeFromTown(ServerLevel level, TownSquareBlockEntity town,
                              Item want, int need) {
      int took = 0;
      var coverage = town.coverage();
      for (var entry : com.yucareux.townfolk.town.StorageRegistry.entries(level)) {
         if (took >= need) break;
         BlockPos pos = BlockPos.of(entry.getKey());
         if (!coverage.contains(pos)) continue;
         var be = level.getBlockEntity(pos);
         if (!(be instanceof net.minecraft.world.Container c)) continue;
         for (int i = 0; i < c.getContainerSize() && took < need; i++) {
            ItemStack s = c.getItem(i);
            if (s.getItem() != want || s.isEmpty()) continue;
            int slotTake = Math.min(s.getCount(), need - took);
            s.shrink(slotTake);
            took += slotTake;
         }
         // Tell the BE its inventory changed so it persists + neighbours
         // re-render (hoppers, comparators, etc.).
         be.setChanged();
      }
      return took;
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

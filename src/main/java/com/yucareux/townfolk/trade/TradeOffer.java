package com.yucareux.townfolk.trade;

import net.minecraft.nbt.CompoundTag;

/**
 * One trade offer posted at a town's Trade Post.
 *
 * <p>Status lifecycle:
 * <ul>
 *   <li>{@code "active"} — open, the player can fulfil. Default state.
 *   <li>{@code "fulfilled"} — player delivered the items; archived on
 *       the next compaction pass.
 *   <li>{@code "expired"} — passed expiry day without being fulfilled.
 *       Archived next pass.
 * </ul>
 *
 * <p>Records are immutable. State transitions produce a fresh record
 * with the new status via {@link #withStatus(String)}.
 */
public record TradeOffer(
   String id,
   VisitorArchetype archetype,
   TradeTier tier,
   String requestItemId,
   int requestCount,
   int paymentEmeralds,
   String flavorBlurb,
   long createdDay,
   long expireDay,
   String status
) {

   public static final String STATUS_ACTIVE    = "active";
   public static final String STATUS_FULFILLED = "fulfilled";
   public static final String STATUS_EXPIRED   = "expired";

   public boolean isActive() { return STATUS_ACTIVE.equals(status); }

   public TradeOffer withStatus(String newStatus) {
      return new TradeOffer(id, archetype, tier, requestItemId, requestCount,
         paymentEmeralds, flavorBlurb, createdDay, expireDay, newStatus);
   }

   public TradeOffer withFlavor(String newBlurb) {
      return new TradeOffer(id, archetype, tier, requestItemId, requestCount,
         paymentEmeralds, newBlurb == null ? "" : newBlurb,
         createdDay, expireDay, status);
   }

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putString("id", id);
      tag.putString("archetype", archetype.name());
      tag.putString("tier", tier.name());
      tag.putString("item", requestItemId);
      tag.putInt("count", requestCount);
      tag.putInt("payment", paymentEmeralds);
      tag.putString("flavor", flavorBlurb == null ? "" : flavorBlurb);
      tag.putLong("createdDay", createdDay);
      tag.putLong("expireDay", expireDay);
      tag.putString("status", status);
      return tag;
   }

   /** Returns null if the saved tag references an unknown archetype or
    *  tier (e.g. a removed enum value). Caller should skip that entry. */
   public static TradeOffer load(CompoundTag tag) {
      VisitorArchetype a = VisitorArchetype.safeFromName(tag.getString("archetype"));
      TradeTier t = TradeTier.safeFromName(tag.getString("tier"));
      if (a == null || t == null) return null;
      return new TradeOffer(
         tag.getString("id"),
         a, t,
         tag.getString("item"),
         tag.getInt("count"),
         tag.getInt("payment"),
         tag.contains("flavor") ? tag.getString("flavor") : "",
         tag.getLong("createdDay"),
         tag.getLong("expireDay"),
         tag.contains("status") ? tag.getString("status") : STATUS_ACTIVE
      );
   }
}

package com.yucareux.townfolk.trade;

/**
 * Tiered prestige bands for trade offers. Common is always available;
 * Notable and Premium unlock as the town's prestige grows.
 *
 * <p>Each tier defines:
 * <ul>
 *   <li>{@link #paymentMultiplier} — base reward × this = emeralds paid.
 *   <li>{@link #expireDays} — how long the offer lingers before expiring.
 *   <li>{@link #quantityMin} / {@link #quantityMax} — request size band.
 *   <li>{@link #prestigeRequired} — minimum prestige for this tier to roll.
 *   <li>{@link #prestigeOnFulfill} / {@link #prestigeOnExpire} — delta
 *       applied to the town when the offer is fulfilled or expires.
 * </ul>
 *
 * <p>Numbers are tuned for Stage 3 v1; expect rebalancing.
 */
public enum TradeTier {
   COMMON  (1, 30,  1, 16,    0,  +2, -1),
   NOTABLE (2, 14, 16, 64,   50,  +5, -3),
   PREMIUM (4,  7,  8, 32,  200, +12, -8);

   public final int paymentMultiplier;
   public final int expireDays;
   public final int quantityMin;
   public final int quantityMax;
   public final int prestigeRequired;
   public final int prestigeOnFulfill;
   public final int prestigeOnExpire;

   TradeTier(int paymentMultiplier, int expireDays,
             int quantityMin, int quantityMax,
             int prestigeRequired,
             int prestigeOnFulfill, int prestigeOnExpire) {
      this.paymentMultiplier = paymentMultiplier;
      this.expireDays = expireDays;
      this.quantityMin = quantityMin;
      this.quantityMax = quantityMax;
      this.prestigeRequired = prestigeRequired;
      this.prestigeOnFulfill = prestigeOnFulfill;
      this.prestigeOnExpire = prestigeOnExpire;
   }

   /** Tolerant {@link #valueOf}: returns {@code null} for unknown names. */
   public static TradeTier safeFromName(String name) {
      if (name == null) return null;
      for (TradeTier t : values()) if (t.name().equals(name)) return t;
      return null;
   }
}
